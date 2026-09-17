package com.homes.zipsai.conversation.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.service.ResidentRoomService;
import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.conversation.dto.response.MessageResponse;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.conversation.repository.MessageRepository;
import com.homes.zipsai.global.exception.NotFoundException;
import com.homes.zipsai.global.util.TextUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final int TITLE_MAX_LENGTH = 30;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ResidentRoomService residentRoomService;

    @Transactional
    public PendingAiReply saveFirstMessage(Long userId, String content) {
        Room room = residentRoomService.getLivingRoom(userId);
        Conversation conversation = conversationRepository.save(Conversation.builder()
            .user(room.getResident())
            .type(ConversationType.INQUIRY)
            .title(TextUtils.truncate(content, TITLE_MAX_LENGTH))
            .build());
        Message residentMessage = saveMessage(conversation, content, SenderType.RESIDENT, MessageType.TEXT);
        AiConverseRequest aiRequest = AiConverseRequest.of(room, conversation, residentMessage, List.of());
        return new PendingAiReply(conversation, MessageResponse.from(residentMessage), true, null, aiRequest);
    }

    @Transactional
    public PendingAiReply saveNextMessage(Long userId, Long conversationId, String content) {
        Conversation conversation = getOwnedConversation(userId, conversationId);
        conversation.verifyCanSendMessage();
        Room room = residentRoomService.getLivingRoom(userId);
        List<Message> history = messageRepository.findAllByConversationId(conversationId);
        LocalDateTime previousLastMessageAt = conversation.getLastMessageAt();
        Message residentMessage = saveMessage(conversation, content, SenderType.RESIDENT, MessageType.TEXT);
        AiConverseRequest aiRequest = AiConverseRequest.of(room, conversation, residentMessage, history);
        return new PendingAiReply(conversation, MessageResponse.from(residentMessage), false, previousLastMessageAt,
            aiRequest);
    }

    @Transactional
    public MessageResponse saveAiReply(PendingAiReply pendingReply, AiConverseResponse aiResponse) {
        Conversation conversation = conversationRepository.getReferenceById(pendingReply.conversation().getId());
        conversation.applyAiResponse(aiResponse.route(), aiResponse.nextState(), aiResponse.complaintDraft());
        MessageType messageType = conversation.isReadyToConfirmComplaint() ? MessageType.SUMMARY_CARD : MessageType.TEXT;
        String reply = TextUtils.truncate(aiResponse.reply(), Message.CONTENT_MAX_LENGTH);
        return MessageResponse.from(saveMessage(conversation, reply, SenderType.ASSISTANT, messageType));
    }

    @Transactional
    public void discardUnansweredMessage(PendingAiReply pendingReply) {
        Long conversationId = pendingReply.conversation().getId();
        messageRepository.deleteById(pendingReply.residentMessage().messageId());
        if (pendingReply.newConversation()) {
            conversationRepository.deleteById(conversationId);
            return;
        }
        conversationRepository.findById(conversationId)
            .ifPresent(conversation -> conversation.updateLastMessageAt(pendingReply.previousLastMessageAt()));
    }

    public Conversation getOwnedConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationRepository.findByIdAndDeletedAtIsNull(conversationId)
            .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.CONVERSATION));
        conversation.verifyOwnedBy(userId);
        return conversation;
    }

    private Message saveMessage(Conversation conversation, String content, SenderType senderType,
                                MessageType messageType) {
        Message message = messageRepository.save(Message.builder()
            .conversation(conversation)
            .content(content)
            .senderType(senderType)
            .messageType(messageType)
            .build());
        conversation.updateLastMessageAt(message.getCreatedAt());
        return message;
    }
}
