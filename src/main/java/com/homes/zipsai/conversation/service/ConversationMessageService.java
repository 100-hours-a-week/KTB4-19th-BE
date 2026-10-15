package com.homes.zipsai.conversation.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.homes.zipsai.conversation.dto.response.ConversationCreateResponse;
import com.homes.zipsai.conversation.dto.response.MessageSendResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConversationMessageService {

    private final ConversationService conversationService;
    private final ConversationReplyService conversationReplyService;
    private final ConversationReplyLock conversationReplyLock;

    public ConversationCreateResponse createConversation(Long userId, String content, List<Long> attachmentIds) {
        PendingAiReply pendingReply = conversationService.saveFirstMessage(userId, content, attachmentIds);
        Long conversationId = pendingReply.conversation().getId();
        String lockToken;
        try {
            lockToken = conversationReplyLock.acquire(conversationId);
        } catch (RuntimeException e) {
            conversationService.discardUnansweredMessage(pendingReply);
            throw e;
        }
        conversationReplyService.reply(userId, pendingReply, lockToken);
        return new ConversationCreateResponse(conversationId);
    }

    public MessageSendResponse sendMessage(Long userId, Long conversationId, String content,
                                           List<Long> attachmentIds) {
        String lockToken = conversationReplyLock.acquire(conversationId);
        PendingAiReply pendingReply;
        try {
            pendingReply = conversationService.saveNextMessage(userId, conversationId, content, attachmentIds);
        } catch (RuntimeException e) {
            conversationReplyLock.release(conversationId, lockToken);
            throw e;
        }
        conversationReplyService.reply(userId, pendingReply, lockToken);
        return MessageSendResponse.of(conversationId, pendingReply.residentMessage());
    }
}
