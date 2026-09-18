package com.homes.zipsai.conversation.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.homes.zipsai.conversation.ai.AiConverseClient;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.dto.request.ConversationCreateRequest;
import com.homes.zipsai.conversation.dto.request.MessageSendRequest;
import com.homes.zipsai.conversation.dto.response.ConversationCreateResponse;
import com.homes.zipsai.conversation.dto.response.MessageResponse;
import com.homes.zipsai.conversation.dto.response.MessageSendResponse;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.InternalServerException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConversationMessageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationMessageService.class);

    private final ConversationService conversationService;
    private final AiConverseClient aiConverseClient;
    private final Set<Long> conversationsWaitingForAi = ConcurrentHashMap.newKeySet();

    public ConversationCreateResponse createConversation(Long userId, ConversationCreateRequest request) {
        PendingAiReply pendingReply = conversationService.saveFirstMessage(userId, request.content());
        MessageResponse assistantMessage = askAiAndSaveReply(pendingReply);
        return ConversationCreateResponse.of(
            pendingReply.conversation(), pendingReply.residentMessage(), assistantMessage);
    }

    public MessageSendResponse sendMessage(Long userId, Long conversationId, MessageSendRequest request) {
        if (!conversationsWaitingForAi.add(conversationId)) {
            throw new ConflictException(ConflictException.Reason.CONVERSATION_BUSY);
        }
        try {
            PendingAiReply pendingReply = conversationService.saveNextMessage(
                userId, conversationId, request.content());
            MessageResponse assistantMessage = askAiAndSaveReply(pendingReply);
            return MessageSendResponse.of(conversationId, pendingReply.residentMessage(), assistantMessage);
        } finally {
            conversationsWaitingForAi.remove(conversationId);
        }
    }

    private MessageResponse askAiAndSaveReply(PendingAiReply pendingReply) {
        try {
            String traceId = pendingReply.aiRequest().traceId();
            AiConverseResponse aiResponse = aiConverseClient.converse(pendingReply.aiRequest());
            verifyPairedWithRequest(traceId, aiResponse);
            return conversationService.saveAiReply(pendingReply, aiResponse);
        } catch (RuntimeException e) {
            conversationService.discardUnansweredMessage(pendingReply);
            throw e;
        }
    }

    private void verifyPairedWithRequest(String traceId, AiConverseResponse aiResponse) {
        if (aiResponse.isSuccess() && traceId.equals(aiResponse.traceId())) {
            return;
        }
        LOGGER.error("AI 응답이 요청과 짝이 맞지 않습니다. requestTraceId={}, responseTraceId={}, code={}",
            traceId, aiResponse.traceId(), aiResponse.code());
        throw new InternalServerException();
    }
}