package com.homes.zipsai.conversation.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.service.ResidentRoomService;
import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.conversation.dto.response.ConversationListItemResponse;
import com.homes.zipsai.conversation.dto.response.ConversationListResponse;
import com.homes.zipsai.conversation.dto.response.ConversationMessagesResponse;
import com.homes.zipsai.conversation.dto.response.ConversationStatusResponse;
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

    @Transactional(readOnly = true)
    public ConversationListResponse getConversations(Long userId, String keyword, String cursor, int size) {
        String searchKeyword = StringUtils.hasText(keyword) ? keyword.strip() : null;
        Limit limit = Limit.of(size + 1);
        List<Conversation> found;
        if (cursor == null) {
            found = conversationRepository.findLatestByUserId(userId, searchKeyword, limit);
        } else {
            ConversationCursor position = ConversationCursor.parse(cursor);
            found = conversationRepository.findLatestByUserIdBefore(
                userId, searchKeyword, position.lastMessageAt(), position.conversationId(), limit);
        }

        boolean hasNext = found.size() > size;
        List<Conversation> conversations = hasNext ? found.subList(0, size) : found;
        String nextCursor = hasNext ? ConversationCursor.from(conversations.getLast()).encode() : null;

        Map<Long, Complaint> complaints = findComplaints(conversations);
        List<ConversationListItemResponse> items = new ArrayList<>();
        for (Conversation conversation : conversations) {
            Complaint complaint = complaints.get(conversation.getId());
            items.add(ConversationListItemResponse.of(conversation, complaint));
        }
        return new ConversationListResponse(hasNext, nextCursor, items);
    }

    @Transactional(readOnly = true)
    public ConversationMessagesResponse getMessages(Long userId, Long conversationId, Long cursor, int size) {
        Conversation conversation = getOwnedConversation(userId, conversationId);
        Limit limit = Limit.of(size + 1);
        List<Message> found;
        if (cursor == null) {
            found = messageRepository.findLatestByConversationId(conversationId, limit);
        } else {
            found = messageRepository.findLatestByConversationIdBefore(
                conversationId, cursor, limit);
        }

        boolean hasNext = found.size() > size;
        List<Message> latestMessages = hasNext ? found.subList(0, size) : found;
        Long nextCursor = hasNext ? latestMessages.getLast().getId() : null;
        List<MessageResponse> messages = latestMessages.reversed().stream().map(MessageResponse::from).toList();

        Complaint complaint = findComplaints(List.of(conversation)).get(conversationId);
        return ConversationMessagesResponse.of(conversation, complaint, messages, hasNext, nextCursor);
    }

    @Transactional
    public ConversationStatusResponse resolveConversation(Long userId, Long conversationId) {
        Conversation conversation = getOwnedConversation(userId, conversationId);
        conversation.resolve();
        conversationRepository.flush();
        return ConversationStatusResponse.from(conversation);
    }

    @Transactional
    public PendingAiReply saveFirstMessage(Long userId, String content) {
        Room room = residentRoomService.getLivingRoom(userId);
        Conversation conversation = conversationRepository.save(Conversation.builder()
            .user(room.getResident())
            .type(ConversationType.INQUIRY)
            .title(TextUtils.truncate(content, TITLE_MAX_LENGTH))
            .build());
        Message residentMessage = saveResidentMessage(conversation, content);
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
        Message residentMessage = saveResidentMessage(conversation, content);
        AiConverseRequest aiRequest = AiConverseRequest.of(room, conversation, residentMessage, history);
        return new PendingAiReply(conversation, MessageResponse.from(residentMessage), false, previousLastMessageAt,
            aiRequest);
    }

    @Transactional
    public MessageResponse saveAiReply(PendingAiReply pendingReply, AiConverseResponse aiResponse) {
        Conversation conversation = conversationRepository.getReferenceById(pendingReply.conversation().getId());
        conversation.applyAiResponse(aiResponse.route(), aiResponse.nextState(), aiResponse.complaintDraft());
        MessageType messageType = conversation.isReadyToConfirmComplaint()
            ? MessageType.SUMMARY_CARD
            : MessageType.TEXT;
        String reply = TextUtils.truncate(aiResponse.reply(), Message.CONTENT_MAX_LENGTH);
        String traceId = pendingReply.aiRequest().traceId();
        Message assistantMessage = saveMessage(conversation, reply, SenderType.ASSISTANT, messageType, traceId);
        return MessageResponse.from(assistantMessage);
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

    private Map<Long, Complaint> findComplaints(List<Conversation> conversations) {
        List<Long> conversationIds = conversations.stream().map(Conversation::getId).toList();
        Map<Long, Complaint> complaints = new HashMap<>();
        if (conversationIds.isEmpty()) {
            return complaints;
        }
        for (Complaint complaint : conversationRepository.findComplaintsByConversationIds(conversationIds)) {
            complaints.put(complaint.getConversation().getId(), complaint);
        }
        return complaints;
    }

    private Message saveResidentMessage(Conversation conversation, String content) {
        String traceId = UUID.randomUUID().toString();
        return saveMessage(conversation, content, SenderType.RESIDENT, MessageType.TEXT, traceId);
    }

    private Message saveMessage(Conversation conversation, String content, SenderType senderType,
                                MessageType messageType, String traceId) {
        Message message = messageRepository.save(Message.builder()
            .conversation(conversation)
            .content(content)
            .senderType(senderType)
            .messageType(messageType)
            .traceId(traceId)
            .build());
        conversation.updateLastMessageAt(message.getCreatedAt());
        return message;
    }
}