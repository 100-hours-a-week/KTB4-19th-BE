package com.homes.zipsai.conversation.service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

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
import com.homes.zipsai.conversation.outbox.ChatbotReplyWorkerProperties;
import com.homes.zipsai.global.config.AsyncConfig;
import com.homes.zipsai.global.exception.ApiException;
import com.homes.zipsai.global.exception.InternalServerException;
import com.homes.zipsai.global.logging.StructuredLogger;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(ChatbotReplyWorkerProperties.class)
public class ConversationReplyService {

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
        conversationService.discardUnansweredMessage(eventId, attempt, lockToken).ifPresent(discarded -> {
            conversationReplyLock.release(discarded.conversationId(), lockToken);
            publish(failedEvent(discarded.userId(), discarded.conversationId(), new InternalServerException()));
        });
    }

    private Optional<ChatbotReplyEvent> askAiForReply(Long userId, PendingAiReply pendingReply) {
        Long conversationId = pendingReply.conversation().getId();
        try {
            return askAiAndSaveReply(userId, pendingReply).map(reply -> ChatbotReplyEvent.of(userId, reply));
        } catch (RuntimeException e) {
            structuredLogger.chatbotReplyFailed(pendingReply.eventId(), conversationId,
                pendingReply.aiRequest().turnId(), e);
            if (chatbotReplyShutdownSignal.isShuttingDown()) {
                return Optional.empty();
            }
            Optional<DiscardedMessage> discarded = e instanceof AiConverseException aiException
                ? conversationService.handleAiReplyFailure(pendingReply, aiException.getErrorType())
                : conversationService.discardUnansweredMessage(pendingReply.eventId(), pendingReply.attempt(),
                    pendingReply.lockToken());
            if (discarded.isEmpty()) {
                return Optional.empty();
            }
            ApiException error = e instanceof ApiException apiException ? apiException : new InternalServerException();
            return Optional.of(failedEvent(userId, conversationId, error));
        }
    }

    private ChatbotReplyEvent failedEvent(Long userId, Long conversationId, ApiException exception) {
        MDC.put(StructuredLogger.ERROR_CODE, exception.code);
        return ChatbotReplyEvent.of(userId, ConversationReplyFailedResponse.of(conversationId, exception));
    }

    private Optional<ConversationReplyResponse> askAiAndSaveReply(Long userId, PendingAiReply pendingReply) {
        long startedAt = System.nanoTime();
        Optional<Duration> remaining = conversationService.remainingReplyTime(pendingReply);
        if (remaining.isEmpty()) {
            return Optional.empty();
        }
        String turnId = pendingReply.aiRequest().turnId();
        Consumer<String> onReplyDelta = replyDeltaSender(userId, pendingReply.conversation().getId());
        AiConverseResponse aiResponse = aiConverseClient.converse(pendingReply.aiRequest(), onReplyDelta);
        if (aiResponse == null) {
            throw new AiConverseException(AiConverseErrorType.INVALID_RESPONSE,
                new InternalServerException(), null);
        }
        aiResponse.validateFor(turnId);
        return saveAiReplyWithRetry(pendingReply, aiResponse, startedAt, remaining.get());
    }

    private Optional<ConversationReplyResponse> saveAiReplyWithRetry(PendingAiReply pendingReply,
                                                                    AiConverseResponse aiResponse,
                                                                    long startedAt, Duration timeout) {
        Duration delay = Duration.ofSeconds(1);
        while (!chatbotReplyShutdownSignal.isShuttingDown() && !Thread.currentThread().isInterrupted()) {
            try {
                return conversationService.saveAiReply(pendingReply, aiResponse);
            } catch (DataAccessException | TransactionException exception) {
                structuredLogger.chatbotReplyFailed(pendingReply.eventId(), pendingReply.conversation().getId(),
                    pendingReply.aiRequest().turnId(), exception);
                if (chatbotReplyShutdownSignal.isShuttingDown() || Thread.currentThread().isInterrupted()) {
                    return Optional.empty();
                }
                Duration remaining = timeout.minusNanos(System.nanoTime() - startedAt);
                if (remaining.isNegative() || remaining.isZero()) {
                    throw new InternalServerException();
                }
                Duration wait = delay.compareTo(remaining) < 0 ? delay : remaining;
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
                if (chatbotReplyShutdownSignal.isShuttingDown()) {
                    return Optional.empty();
                }
                remaining = timeout.minusNanos(System.nanoTime() - startedAt);
                if (remaining.isNegative() || remaining.isZero()) {
                    throw new InternalServerException();
                }
                delay = Duration.ofSeconds(Math.min(delay.toSeconds() * 2, 10));
            }
        }
        return Optional.empty();
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
