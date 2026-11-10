package com.homes.zipsai.conversation.consumer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.error.AiConverseErrorType.RetryPolicy;
import com.homes.zipsai.conversation.ai.error.AiConverseException;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutboxStatus;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.outbox.ChatbotReplyWorker;
import com.homes.zipsai.conversation.outbox.ChatbotReplyWorkerProperties;
import com.homes.zipsai.conversation.repository.ChatbotReplyOutboxRepository;
import com.homes.zipsai.conversation.service.ConversationReplyService;
import com.homes.zipsai.conversation.service.ConversationService;
import com.homes.zipsai.conversation.service.PendingAiReply;
import com.homes.zipsai.global.logging.StructuredLogger;
import com.rabbitmq.client.Channel;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

@Component
@RequiredArgsConstructor
public class ChatbotReplyRequestListener implements ChannelAwareMessageListener {

    private final ChatbotReplyWorker chatbotReplyWorker;
    private final ConversationReplyService conversationReplyService;
    private final ConversationService conversationService;
    private final ChatbotReplyOutboxRepository chatbotReplyOutboxRepository;
    private final ObjectMapper objectMapper;
    private final StructuredLogger structuredLogger;
    private final ChatbotReplyWorkerProperties chatbotReplyWorkerProperties;

    @Override
    public void onMessage(Message message, Channel channel) throws IOException {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        try {
            long deliveryTag = message.getMessageProperties().getDeliveryTag();
            RequestMessage request;
            JsonNode body = null;
            try {
                body = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .readTree(message.getBody());
                request = parseRequest(body);
            } catch (JacksonException | IllegalArgumentException exception) {
                Long eventId = null;
                Long conversationId = null;
                String turnId = null;
                if (body != null) {
                    JsonNode event = body.path("eventId");
                    if (event.isIntegralNumber() && event.canConvertToLong()) {
                        eventId = event.longValue();
                    }
                    JsonNode payload = body.path("request");
                    JsonNode conversation = payload.path("conversation_id");
                    if (conversation.isString()) {
                        try {
                            conversationId = Long.valueOf(conversation.asString());
                        } catch (NumberFormatException ignored) {

                        }
                    }
                    JsonNode turn = payload.path("turn_id");
                    if (turn.isString()) {
                        turnId = turn.asString();
                    }
                }
                structuredLogger.chatbotReplyFailed(eventId, conversationId, turnId, exception);
                channel.basicReject(deliveryTag, false);
                return;
            }
            switch (process(request.eventId(), request.request())) {
                case ACK -> channel.basicAck(deliveryTag, false);
                case NACK -> channel.basicNack(deliveryTag, false, true);
                case REJECT_REQUEUE -> channel.basicReject(deliveryTag, true);
                case REJECT -> channel.basicReject(deliveryTag, false);
            }
        } finally {
            if (previousMdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previousMdc);
            }
        }
    }

    private RequestMessage parseRequest(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 2 || !body.has("eventId")
                || !body.get("eventId").isIntegralNumber() || !body.get("eventId").canConvertToLong()
                || !body.has("request") || !body.get("request").isObject()) {
            throw new IllegalArgumentException("Expected a numeric eventId and a request object");
        }
        return new RequestMessage(body.get("eventId").longValue(),
            objectMapper.readerFor(AiConverseRequest.class)
                .without(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .readValue(body.get("request")));
    }

    private DeliveryAction process(long eventId, AiConverseRequest request) {
        Long conversationId = null;
        String turnId = request.turnId();
        try {
            conversationId = Long.valueOf(request.conversationId());
        } catch (NumberFormatException ignored) {

        }
        Optional<ChatbotReplyOutbox> result;
        try {
            result = chatbotReplyOutboxRepository.findById(eventId);
        } catch (DataAccessException | TransactionException exception) {
            structuredLogger.chatbotReplyFailed(eventId, conversationId, turnId, exception);
            return DeliveryAction.REJECT_REQUEUE;
        }
        if (result.isEmpty()) {
            structuredLogger.chatbotReplyFailed(eventId, conversationId, turnId,
                new IllegalArgumentException("Chatbot reply outbox not found"));
            return DeliveryAction.REJECT;
        }
        ChatbotReplyOutbox outbox = result.orElseThrow();
        MDC.put("traceId", outbox.getTraceId());
        if (isTerminal(outbox.getStatus())) {
            return DeliveryAction.ACK;
        }
        Optional<PendingAiReply> prepared;
        try {
            prepared = chatbotReplyWorker.claimAndPrepareReply(eventId, request);
        } catch (DataAccessException | TransactionException exception) {
            return DeliveryAction.REJECT_REQUEUE;
        } catch (RuntimeException exception) {
            return unclaimedAction(eventId, conversationId, turnId, DeliveryAction.REJECT_REQUEUE);
        }
        if (prepared.isEmpty()) {
            return unclaimedAction(eventId, conversationId, turnId, DeliveryAction.NACK);
        }
        PendingAiReply pending = prepared.orElseThrow();
        try {
            if (conversationReplyService.replySynchronously(pending).isPresent()) {
                return DeliveryAction.ACK;
            }
        } catch (RuntimeException exception) {
            return failureAction(pending, exception);
        }
        return unclaimedAction(eventId, conversationId, turnId, DeliveryAction.NACK);
    }

    private DeliveryAction unclaimedAction(long eventId, Long conversationId, String turnId, DeliveryAction fallback) {
        try {
            Optional<ChatbotReplyOutbox> result = chatbotReplyOutboxRepository.findById(eventId);
            if (result.isEmpty()) {
                structuredLogger.chatbotReplyFailed(eventId, conversationId, turnId,
                    new IllegalArgumentException("Chatbot reply outbox not found"));
                return DeliveryAction.REJECT;
            }
            if (isTerminal(result.orElseThrow().getStatus())) {
                return DeliveryAction.ACK;
            }
            return isExpired(eventId) ? DeliveryAction.REJECT : fallback;
        } catch (DataAccessException | TransactionException exception) {
            structuredLogger.chatbotReplyFailed(eventId, conversationId, turnId, exception);
            return DeliveryAction.REJECT_REQUEUE;
        }
    }

    private DeliveryAction failureAction(PendingAiReply pending, RuntimeException exception) {
        structuredLogger.chatbotReplyFailed(pending.eventId(), pending.conversation().getId(),
            pending.aiRequest().turnId(), exception);
        RetryPolicy policy = exception instanceof AiConverseException aiException
            ? aiException.getErrorType().getRetryPolicy() : RetryPolicy.UNLIMITED;
        try {
            if (policy == RetryPolicy.NONE || isExpired(pending.eventId())) {
                return DeliveryAction.REJECT;
            }
            ChatbotReplyOutboxStatus status = conversationService.retryReply(pending, policy);
            return isTerminal(status) ? DeliveryAction.ACK : DeliveryAction.REJECT_REQUEUE;
        } catch (DataAccessException | TransactionException retryException) {
            structuredLogger.chatbotReplyFailed(pending.eventId(), pending.conversation().getId(),
                pending.aiRequest().turnId(), retryException);
            return DeliveryAction.REJECT_REQUEUE;
        }
    }

    private boolean isExpired(long eventId) {
        LocalDateTime now = chatbotReplyOutboxRepository.currentTime();
        return chatbotReplyOutboxRepository.findCleanupCandidate(eventId,
            now.minus(chatbotReplyWorkerProperties.requestTimeout()),
            now.minus(Conversation.IDLE_TIME_TO_CLOSE)).isPresent();
    }

    private boolean isTerminal(ChatbotReplyOutboxStatus status) {
        return status == ChatbotReplyOutboxStatus.COMPLETED || status == ChatbotReplyOutboxStatus.FAILED;
    }

    private enum DeliveryAction {
        ACK, NACK, REJECT_REQUEUE, REJECT
    }

    private record RequestMessage(long eventId, AiConverseRequest request) {
    }
}
