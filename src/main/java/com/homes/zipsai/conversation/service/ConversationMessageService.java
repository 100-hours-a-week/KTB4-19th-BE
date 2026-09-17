package com.homes.zipsai.conversation.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.homes.zipsai.conversation.ai.AiConverseClient;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.dto.request.ConversationCreateRequest;
import com.homes.zipsai.conversation.dto.request.MessageSendRequest;
import com.homes.zipsai.conversation.dto.response.ConversationCreateResponse;
import com.homes.zipsai.conversation.dto.response.MessageResponse;
import com.homes.zipsai.conversation.dto.response.MessageSendResponse;
import com.homes.zipsai.global.exception.ConflictException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConversationMessageService {

    private final ConversationService conversationService;
    private final AiConverseClient aiConverseClient;
    private final Set<Long> conversationsWaitingForAi = ConcurrentHashMap.newKeySet();

    public ConversationCreateResponse createConversation(Long userId, ConversationCreateRequest request) {
        PendingAiReply pendingReply = conversationService.saveFirstMessage(userId, request.content());
        MessageResponse assistantMessage = askAiAndSaveReply(pendingReply);
        return ConversationCreateResponse.of(pendingReply.conversation(), pendingReply.residentMessage(), assistantMessage);
    }

    public MessageSendResponse sendMessage(Long userId, Long conversationId, MessageSendRequest request) {
        if (!conversationsWaitingForAi.add(conversationId)) {
            throw new ConflictException(ConflictException.Reason.CONVERSATION_BUSY);
        }
        try {
            PendingAiReply pendingReply = conversationService.saveNextMessage(userId, conversationId, request.content());
            MessageResponse assistantMessage = askAiAndSaveReply(pendingReply);
            return MessageSendResponse.of(conversationId, pendingReply.residentMessage(), assistantMessage);
        } finally {
            conversationsWaitingForAi.remove(conversationId);
        }
    }

    private MessageResponse askAiAndSaveReply(PendingAiReply pendingReply) {
        try {
            AiConverseResponse aiResponse = aiConverseClient.converse(pendingReply.aiRequest());
            return conversationService.saveAiReply(pendingReply, aiResponse);
        } catch (RuntimeException e) {
            conversationService.discardUnansweredMessage(pendingReply);
            throw e;
        }
    }
}
