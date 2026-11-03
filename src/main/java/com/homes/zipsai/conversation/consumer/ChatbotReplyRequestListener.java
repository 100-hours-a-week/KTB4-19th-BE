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

import com.homes.zipsai.conversation.ai.error.AiConverseException;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutboxStatus;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.outbox.ChatbotReplyWorker;
import com.homes.zipsai.conversation.outbox.ChatbotReplyWorkerProperties;
import com.homes.zipsai.conversation.repository.ChatbotReplyOutboxRepository;
import com.homes.zipsai.conversation.service.ConversationReplyService;
import com.homes.zipsai.conversation.service.ConversationService;
import com.homes.zipsai.conversation.service.DiscardedMessage;
import com.homes.zipsai.conversation.service.PendingAiReply;
import com.homes.zipsai.global.exception.InternalServerException;
import com.homes.zipsai.global.logging.StructuredLogger;
import com.rabbitmq.client.Channel;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
            long eventId = eventId(message);
            long deliveryTag = message.getMessageProperties().getDeliveryTag();
            if (process(eventId)) {
                channel.basicAck(deliveryTag, false);
            } else {
                channel.basicNack(deliveryTag, false, true);
            }
        } finally {
            if (previousMdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previousMdc);
            }
        }
    }

    private long eventId(Message message) {
        JsonNode body = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .readTree(message.getBody());
        if (body == null || !body.isObject() || body.size() != 1 || !body.has("eventId")
                || !body.get("eventId").isIntegralNumber() || !body.get("eventId").canConvertToLong()) {
            throw new IllegalArgumentException("Expected a single numeric eventId");
        }
        return body.get("eventId").longValue();
    }

    private boolean process(long eventId) {
        ChatbotReplyOutbox outbox;
        try {
            outbox = chatbotReplyOutboxRepository.findById(eventId).orElseThrow();
        } catch (DataAccessException | TransactionException exception) {
            structuredLogger.chatbotReplyFailed(eventId, null, null, exception);
            return false;
        }
        MDC.put("traceId", outbox.getTraceId());
        if (isTerminal(outbox)) {
            return true;
        }
        Optional<PendingAiReply> prepared;
        try {
            prepared = chatbotReplyWorker.claimAndPrepareReply(eventId);
        } catch (DataAccessException | TransactionException exception) {
            return false;
        } catch (InternalServerException exception) {
            return cleanupUnclaimed(eventId);
        }
        if (prepared.isEmpty()) {
            return cleanupUnclaimed(eventId);
        }
        PendingAiReply pending = prepared.orElseThrow();
        try {
            if (conversationReplyService.replySynchronously(pending).isPresent()) {
                return true;
            }
        } catch (DataAccessException | TransactionException exception) {
            structuredLogger.chatbotReplyFailed(eventId, pending.conversation().getId(),
                pending.aiRequest().turnId(), exception);
            return false;
        } catch (AiConverseException exception) {
            structuredLogger.chatbotReplyFailed(eventId, pending.conversation().getId(),
                pending.aiRequest().turnId(), exception);
            Optional<DiscardedMessage> discardedMessage =
                conversationService.handleAiReplyFailure(pending, exception.getErrorType());
            if (discardedMessage.isPresent()) {
                conversationReplyService.publishFailure(discardedMessage.orElseThrow(), pending.lockToken(), exception);
                return true;
            }
        } catch (RuntimeException exception) {
            structuredLogger.chatbotReplyFailed(eventId, pending.conversation().getId(),
                pending.aiRequest().turnId(), exception);
            conversationReplyService.failReply(eventId, pending.attempt(), pending.lockToken());
        }
        return isTerminal(chatbotReplyOutboxRepository.findById(eventId).orElseThrow());
    }

    private boolean cleanupUnclaimed(long eventId) {
        Optional<ChatbotReplyOutbox> candidate;
        try {
            LocalDateTime now = chatbotReplyOutboxRepository.currentTime();
            candidate = chatbotReplyOutboxRepository.findCleanupCandidate(eventId,
                now.minus(chatbotReplyWorkerProperties.requestTimeout()),
                now.minus(Conversation.IDLE_TIME_TO_CLOSE));
        } catch (DataAccessException | TransactionException exception) {
            structuredLogger.chatbotReplyFailed(eventId, null, null, exception);
            return false;
        }
        candidate.ifPresent(outbox ->
            conversationReplyService.failReply(eventId, outbox.getAttemptCount(), outbox.getLockToken()));
        return isTerminal(chatbotReplyOutboxRepository.findById(eventId).orElseThrow());
    }

    private boolean isTerminal(ChatbotReplyOutbox outbox) {
        return outbox.getStatus() == ChatbotReplyOutboxStatus.COMPLETED
            || outbox.getStatus() == ChatbotReplyOutboxStatus.FAILED;
    }
}
