package com.homes.zipsai.conversation.service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.homes.zipsai.common.service.SseService;
import com.homes.zipsai.conversation.ai.AiConverseClient;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.dto.response.ConversationReplyDeltaResponse;
import com.homes.zipsai.conversation.dto.response.ConversationReplyFailedResponse;
import com.homes.zipsai.conversation.dto.response.ConversationReplyResponse;
import com.homes.zipsai.global.config.AsyncConfig;
import com.homes.zipsai.global.exception.ApiException;
import com.homes.zipsai.global.exception.InternalServerException;
import com.homes.zipsai.global.logging.StructuredLogger;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConversationReplyService {

    static final String REPLY_DELTA_EVENT = "chat-delta";
    static final String REPLY_EVENT = "chat-done";
    static final String REPLY_FAILED_EVENT = "chat-error";

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationReplyService.class);

    private final ConversationService conversationService;
    private final AiConverseClient aiConverseClient;
    private final SseService sseService;
    private final ConversationReplyLock conversationReplyLock;

    @Async(AsyncConfig.CHATBOT_REPLY_EXECUTOR)
    public void reply(Long userId, PendingAiReply pendingReply, String lockToken) {
        sseService.send(userId, replyEvent(userId, pendingReply, lockToken));
    }

    private SseEmitter.SseEventBuilder replyEvent(Long userId, PendingAiReply pendingReply, String lockToken) {
        Long conversationId = pendingReply.conversation().getId();
        try {
            return event(REPLY_EVENT, askAiAndSaveReply(userId, pendingReply));
        } catch (ApiException e) {
            return failedEvent(conversationId, e);
        } catch (RuntimeException e) {
            LOGGER.error("AI 답변을 처리하지 못했습니다. conversationId={}", conversationId, e);
            return failedEvent(conversationId, new InternalServerException());
        } finally {
            conversationReplyLock.release(conversationId, lockToken);
        }
    }

    private SseEmitter.SseEventBuilder failedEvent(Long conversationId, ApiException exception) {
        MDC.put(StructuredLogger.ERROR_CODE, exception.code);
        return event(REPLY_FAILED_EVENT, ConversationReplyFailedResponse.of(conversationId, exception));
    }

    private ConversationReplyResponse askAiAndSaveReply(Long userId, PendingAiReply pendingReply) {
        try {
            String turnId = pendingReply.aiRequest().turnId();
            Consumer<String> onReplyDelta = replyDeltaSender(userId, pendingReply.conversation().getId());
            AiConverseResponse aiResponse = aiConverseClient.converse(pendingReply.aiRequest(), onReplyDelta);
            verifyPairedWithRequest(turnId, aiResponse);
            return conversationService.saveAiReply(pendingReply, aiResponse);
        } catch (RuntimeException e) {
            conversationService.discardUnansweredMessage(pendingReply);
            throw e;
        }
    }

    private Consumer<String> replyDeltaSender(Long userId, Long conversationId) {
        AtomicInteger seq = new AtomicInteger();
        return text -> sseService.send(userId,
            event(REPLY_DELTA_EVENT, new ConversationReplyDeltaResponse(conversationId, seq.incrementAndGet(), text)));
    }

    private void verifyPairedWithRequest(String turnId, AiConverseResponse aiResponse) {
        if (aiResponse.isSuccess() && turnId.equals(aiResponse.turnId())) {
            return;
        }
        LOGGER.error("AI 응답이 요청과 짝이 맞지 않습니다. requestTurnId={}, responseTurnId={}, code={}",
            turnId, aiResponse.turnId(), aiResponse.code());
        throw new InternalServerException();
    }

    private static SseEmitter.SseEventBuilder event(String name, Object data) {
        return SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON);
    }
}
