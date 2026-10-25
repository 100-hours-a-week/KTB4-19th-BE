package com.homes.zipsai.conversation.service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.homes.zipsai.common.service.RedisMessagePublisher;
import com.homes.zipsai.conversation.ai.AiConverseClient;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.ai.error.AiConverseErrorType;
import com.homes.zipsai.conversation.ai.error.AiConverseException;
import com.homes.zipsai.conversation.config.ChatbotReplyPubSubProperties;
import com.homes.zipsai.conversation.dto.response.ConversationReplyDeltaResponse;
import com.homes.zipsai.conversation.dto.response.ConversationReplyFailedResponse;
import com.homes.zipsai.conversation.dto.response.ConversationReplyResponse;
import com.homes.zipsai.conversation.event.ChatbotReplyEvent;
import com.homes.zipsai.global.config.AsyncConfig;
import com.homes.zipsai.global.exception.ApiException;
import com.homes.zipsai.global.exception.InternalServerException;
import com.homes.zipsai.global.logging.StructuredLogger;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConversationReplyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationReplyService.class);

    private final ConversationService conversationService;
    private final AiConverseClient aiConverseClient;
    private final RedisMessagePublisher redisMessagePublisher;
    private final ChatbotReplyPubSubProperties chatbotReplyPubSubProperties;
    private final ConversationReplyLock conversationReplyLock;
    private final ChatbotReplyShutdownSignal chatbotReplyShutdownSignal;
    private final StructuredLogger structuredLogger;

    @Async(AsyncConfig.CHATBOT_REPLY_EXECUTOR)
    public void reply(PendingAiReply pendingReply) {
        askAiForReply(pendingReply.userId(), pendingReply).ifPresent(event -> {
            conversationReplyLock.release(pendingReply.conversation().getId(), pendingReply.lockToken());
            publish(event);
        });
    }

    public void failReply(Long eventId, int attempt, String lockToken) {
        conversationService.discardUnansweredMessage(eventId, attempt).ifPresent(discarded -> {
            conversationReplyLock.release(discarded.conversationId(), lockToken);
            publish(failedEvent(discarded.userId(), discarded.conversationId(), new InternalServerException()));
        });
    }

    private Optional<ChatbotReplyEvent> askAiForReply(Long userId, PendingAiReply pendingReply) {
        Long conversationId = pendingReply.conversation().getId();
        try {
            return askAiAndSaveReply(userId, pendingReply).map(reply -> ChatbotReplyEvent.of(userId, reply));
        } catch (ApiException e) {
            structuredLogger.chatbotReplyFailed(pendingReply.eventId(), conversationId,
                pendingReply.aiRequest().turnId(), e);
            return Optional.of(failedEvent(userId, conversationId, e));
        } catch (RuntimeException e) {
            structuredLogger.chatbotReplyFailed(pendingReply.eventId(), conversationId,
                pendingReply.aiRequest().turnId(), e);
            return Optional.of(failedEvent(userId, conversationId, new InternalServerException()));
        }
    }

    private ChatbotReplyEvent failedEvent(Long userId, Long conversationId, ApiException exception) {
        MDC.put(StructuredLogger.ERROR_CODE, exception.code);
        return ChatbotReplyEvent.of(userId, ConversationReplyFailedResponse.of(conversationId, exception));
    }

    private Optional<ConversationReplyResponse> askAiAndSaveReply(Long userId, PendingAiReply pendingReply) {
        try {
            String turnId = pendingReply.aiRequest().turnId();
            Consumer<String> onReplyDelta = replyDeltaSender(userId, pendingReply.conversation().getId());
            AiConverseResponse aiResponse = aiConverseClient.converse(pendingReply.aiRequest(), onReplyDelta);
            if (aiResponse == null) {
                throw new AiConverseException(AiConverseErrorType.INVALID_RESPONSE,
                    new InternalServerException(), null);
            }
            aiResponse.validateFor(turnId);
            return conversationService.saveAiReply(pendingReply, aiResponse);
        } catch (RuntimeException e) {
            if (chatbotReplyShutdownSignal.isShuttingDown()) {
                LOGGER.warn("서버가 종료되는 중이라 답변 이벤트를 처리 중으로 남깁니다. eventId={}", pendingReply.eventId(), e);
                return Optional.empty();
            }
            Optional<DiscardedMessage> discarded =
                conversationService.discardUnansweredMessage(pendingReply.eventId(), pendingReply.attempt());
            if (discarded.isEmpty()) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private Consumer<String> replyDeltaSender(Long userId, Long conversationId) {
        AtomicInteger seq = new AtomicInteger();
        return text -> publish(ChatbotReplyEvent.of(userId,
            new ConversationReplyDeltaResponse(conversationId, seq.incrementAndGet(), text)));
    }

    private void publish(ChatbotReplyEvent event) {
        redisMessagePublisher.publish(chatbotReplyPubSubProperties.chatbotReplyChannel(), event);
    }
}
