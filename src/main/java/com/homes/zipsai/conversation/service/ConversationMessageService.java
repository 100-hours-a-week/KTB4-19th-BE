package com.homes.zipsai.conversation.service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.homes.zipsai.conversation.ai.AiConverseClient;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.dto.response.ConversationCreateResponse;
import com.homes.zipsai.conversation.dto.response.MessageSendResponse;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.InternalServerException;
import com.homes.zipsai.global.logging.StructuredLogger;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConversationMessageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationMessageService.class);

    private final ConversationService conversationService;
    private final AiConverseClient aiConverseClient;
    private final StructuredLogger structuredLogger;
    private final Set<Long> conversationsWaitingForAi = ConcurrentHashMap.newKeySet();

    public ConversationCreateResponse createConversation(Long userId, String content, List<Long> attachmentIds) {
        PendingAiReply pendingReply = conversationService.saveFirstMessage(userId, content, attachmentIds);
        MessageSendResponse sent = askAiAndSaveReply(pendingReply);
        return new ConversationCreateResponse(sent.conversationId());
    }

    public MessageSendResponse sendMessage(Long userId, Long conversationId, String content,
                                           List<Long> attachmentIds) {
        if (!conversationsWaitingForAi.add(conversationId)) {
            throw new ConflictException(ConflictException.Reason.CONVERSATION_BUSY);
        }
        try {
            long dbStarted = System.nanoTime();
            PendingAiReply pendingReply = conversationService.saveNextMessage(
                userId, conversationId, content, attachmentIds);
            logDbStage(dbStarted);
            return askAiAndSaveReply(pendingReply);
        } finally {
            conversationsWaitingForAi.remove(conversationId);
        }
    }

    private void logDbStage(long started) {
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            structuredLogger.stageDone(traceId, "conversation", "mysql",
                (System.nanoTime() - started) / 1_000_000, "ok");
        }
    }

    private MessageSendResponse askAiAndSaveReply(PendingAiReply pendingReply) {
        try {
            String turnId = pendingReply.aiRequest().turnId();
            AiConverseResponse aiResponse = aiConverseClient.converse(pendingReply.aiRequest());
            verifyPairedWithRequest(turnId, aiResponse);
            return conversationService.saveAiReply(pendingReply, aiResponse);
        } catch (RuntimeException e) {
            conversationService.discardUnansweredMessage(pendingReply);
            throw e;
        }
    }

    private void verifyPairedWithRequest(String turnId, AiConverseResponse aiResponse) {
        if (aiResponse.isSuccess() && turnId.equals(aiResponse.turnId())) {
            return;
        }
        LOGGER.error("AI 응답이 요청과 짝이 맞지 않습니다. requestTurnId={}, responseTurnId={}, code={}",
            turnId, aiResponse.turnId(), aiResponse.code());
        throw new InternalServerException();
    }
}
