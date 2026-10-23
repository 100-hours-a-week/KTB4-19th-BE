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
        conversationReplyService.reply(pendingReply);
        return new ConversationCreateResponse(pendingReply.conversation().getId());
    }

    public MessageSendResponse sendMessage(Long userId, Long conversationId, String content,
                                           List<Long> attachmentIds) {
        String lockToken = conversationReplyLock.acquire(conversationId);
        PendingAiReply pendingReply;
        try {
            pendingReply = conversationService.saveNextMessage(userId, conversationId, content, attachmentIds,
                lockToken);
        } catch (RuntimeException e) {
            conversationReplyLock.release(conversationId, lockToken);
            throw e;
        }
        conversationReplyService.reply(pendingReply);
        return MessageSendResponse.of(conversationId, pendingReply.residentMessage());
    }
}
