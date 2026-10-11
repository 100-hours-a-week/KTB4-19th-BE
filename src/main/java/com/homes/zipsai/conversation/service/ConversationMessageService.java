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

    public ConversationCreateResponse createConversation(Long userId, String content, List<Long> attachmentIds) {
        PendingAiReply pendingReply = conversationService.saveFirstMessage(userId, content, attachmentIds);
        Long conversationId = pendingReply.conversation().getId();
        conversationReplyService.startReplying(conversationId);
        conversationReplyService.reply(userId, pendingReply);
        return new ConversationCreateResponse(conversationId);
    }

    public MessageSendResponse sendMessage(Long userId, Long conversationId, String content,
                                           List<Long> attachmentIds) {
        conversationReplyService.startReplying(conversationId);
        PendingAiReply pendingReply;
        try {
            pendingReply = conversationService.saveNextMessage(userId, conversationId, content, attachmentIds);
        } catch (RuntimeException e) {
            conversationReplyService.finishReplying(conversationId);
            throw e;
        }
        conversationReplyService.reply(userId, pendingReply);
        return MessageSendResponse.of(conversationId, pendingReply.residentMessage());
    }
}
