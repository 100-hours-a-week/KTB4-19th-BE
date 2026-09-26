package com.homes.zipsai.conversation.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.service.ResidentRoomService;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.common.domain.FileStatus;
import com.homes.zipsai.common.repository.FileRepository;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.AiConverseRequest.HistoryMessage;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.MessageFileGroup;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.conversation.dto.response.AttachmentResponse;
import com.homes.zipsai.conversation.dto.response.ConversationListItemResponse;
import com.homes.zipsai.conversation.dto.response.ConversationListResponse;
import com.homes.zipsai.conversation.dto.response.ConversationMessagesResponse;
import com.homes.zipsai.conversation.dto.response.MessageResponse;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.conversation.repository.MessageFileGroupRepository;
import com.homes.zipsai.conversation.repository.MessageRepository;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.global.exception.NotFoundException;
import com.homes.zipsai.global.exception.ValidationFailedException;
import com.homes.zipsai.global.util.TextUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final int TITLE_MAX_LENGTH = 30;
    private static final String IMAGE_ONLY_TITLE = "사진 문의";
    private static final Set<String> IMAGE_TYPES = Set.of("jpg", "jpeg", "png");

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageFileGroupRepository messageFileGroupRepository;
    private final FileRepository fileRepository;
    private final ResidentRoomService residentRoomService;
    private final S3StorageService s3StorageService;
    private final StorageProperties storageProperties;

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

        List<ConversationListItemResponse> items = conversations.stream()
            .map(conversation -> ConversationListItemResponse.from(conversation))
            .toList();
        return new ConversationListResponse(hasNext, nextCursor, items);
    }

    @Transactional(readOnly = true)
    public ConversationMessagesResponse getMessages(Long userId, Long conversationId, Long cursor, int size) {
        Conversation conversation = getOwnedConversation(userId, conversationId);
        return readMessages(conversation, null, cursor, size);
    }

    @Transactional(readOnly = true)
    public ConversationMessagesResponse getComplaintMessagesForManager(Long managerId, Long conversationId,
                                                                      Long cursor, int size) {
        Conversation conversation = conversationRepository.findByIdAndDeletedAtIsNull(conversationId)
            .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.CONVERSATION));
        Complaint complaint = conversationRepository.findComplaintByConversationId(conversationId).orElse(null);
        if (complaint == null || complaint.getBuilding().getDeletedAt() != null) {
            throw new NotFoundException(NotFoundException.Resource.CONVERSATION);
        }
        if (!complaint.getBuilding().getManager().getId().equals(managerId)) {
            throw new ForbiddenException();
        }
        return readMessages(conversation, complaint.getId(), cursor, size);
    }

    private ConversationMessagesResponse readMessages(Conversation conversation, Long complaintId,
                                                      Long cursor, int size) {
        Long conversationId = conversation.getId();
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
        Map<Long, List<AttachmentResponse>> attachments = findAttachments(latestMessages);
        List<MessageResponse> messages = latestMessages.reversed().stream()
            .map(message -> MessageResponse.of(message, attachments.getOrDefault(message.getId(), List.of())))
            .toList();

        return ConversationMessagesResponse.of(conversation, complaintId, messages, hasNext, nextCursor);
    }

    @Transactional
    public PendingAiReply saveFirstMessage(Long userId, String content, List<Long> attachmentIds) {
        Room room = residentRoomService.getLivingRoom(userId);
        List<File> images = getAttachableImages(userId, attachmentIds);

        Conversation conversation = conversationRepository.save(Conversation.builder()
            .user(room.getResident())
            .type(ConversationType.INQUIRY)
            .title(content.isEmpty() ? IMAGE_ONLY_TITLE : TextUtils.truncate(content, TITLE_MAX_LENGTH))
            .build());
        Message residentMessage = saveResidentMessage(conversation, content);
        List<AttachmentResponse> attachments = attachImages(residentMessage, images);

        AiConverseRequest aiRequest = AiConverseRequest.of(room, conversation, residentMessage,
            fileUrls(attachments), List.of());
        return new PendingAiReply(conversation, MessageResponse.of(residentMessage, attachments), true, null,
            aiRequest);
    }

    @Transactional
    public PendingAiReply saveNextMessage(Long userId, Long conversationId, String content,
                                          List<Long> attachmentIds) {
        Conversation conversation = getOwnedConversation(userId, conversationId);
        conversation.verifyCanSendMessage();
        Room room = residentRoomService.getLivingRoom(userId);
        List<File> images = getAttachableImages(userId, attachmentIds);
        List<HistoryMessage> history = getHistory(conversationId);
        LocalDateTime previousLastMessageAt = conversation.getLastMessageAt();

        Message residentMessage = saveResidentMessage(conversation, content);
        List<AttachmentResponse> attachments = attachImages(residentMessage, images);

        AiConverseRequest aiRequest = AiConverseRequest.of(room, conversation, residentMessage,
            fileUrls(attachments), history);
        return new PendingAiReply(conversation, MessageResponse.of(residentMessage, attachments), false,
            previousLastMessageAt, aiRequest);
    }

    @Transactional
    public MessageResponse saveAiReply(PendingAiReply pendingReply, AiConverseResponse aiResponse) {
        Conversation conversation = conversationRepository.getReferenceById(pendingReply.conversation().getId());
        conversation.applyAiResponse(aiResponse);
        MessageType messageType = MessageType.TEXT;
        if (conversation.isReadyToConfirmComplaint()) {
            messageType = MessageType.SUMMARY_CARD;
        }
        String reply = TextUtils.truncate(aiResponse.reply(), Message.CONTENT_MAX_LENGTH);
        String traceId = pendingReply.aiRequest().traceId();
        Message assistantMessage = saveMessage(conversation, reply, SenderType.ASSISTANT, messageType, traceId);
        return MessageResponse.from(assistantMessage);
    }

    @Transactional
    public void discardUnansweredMessage(PendingAiReply pendingReply) {
        Long conversationId = pendingReply.conversation().getId();
        Long residentMessageId = pendingReply.residentMessage().messageId();
        messageFileGroupRepository.deleteAllByMessageId(residentMessageId);
        messageRepository.deleteById(residentMessageId);
        if (pendingReply.newConversation()) {
            conversationRepository.deleteById(conversationId);
            return;
        }
        conversationRepository.findById(conversationId)
            .ifPresent(conversation -> conversation.updateLastMessageAt(pendingReply.previousLastMessageAt()));
    }

    @Transactional(readOnly = true)
    public List<File> findImages(Long conversationId) {
        return messageFileGroupRepository.findAllByConversationId(conversationId, Limit.unlimited()).stream()
            .map(MessageFileGroup::getAttachment)
            .toList();
    }

    @Transactional(readOnly = true)
    public File findRepresentativeImage(Long conversationId) {
        return messageFileGroupRepository.findAllByConversationId(conversationId, Limit.of(1)).stream()
            .map(MessageFileGroup::getAttachment)
            .findFirst()
            .orElse(null);
    }

    public Conversation getOwnedConversation(Long userId, Long conversationId) {
        Conversation conversation = conversationRepository.findByIdAndDeletedAtIsNull(conversationId)
            .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.CONVERSATION));
        conversation.verifyOwnedBy(userId);
        return conversation;
    }

    private List<File> getAttachableImages(Long userId, List<Long> attachmentIds) {
        if (attachmentIds == null) {
            return List.of();
        }

        List<File> images = new ArrayList<>();
        for (Long attachmentId : attachmentIds.stream().distinct().toList()) {
            File file = fileRepository.findById(attachmentId)
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.ATTACHMENT));
            verifyAttachableImage(file, userId);
            images.add(file);
        }
        return images;
    }

    private void verifyAttachableImage(File file, Long userId) {
        if (file.getOwner() == null || !file.getOwner().getId().equals(userId)) {
            throw new ForbiddenException();
        }
        if (file.getStatus() != FileStatus.UPLOADED) {
            throw new ConflictException(ConflictException.Reason.UPLOAD_NOT_COMPLETED);
        }
        if (!IMAGE_TYPES.contains(file.getFileType())) {
            throw new ValidationFailedException("attachmentIds", ValidationFailedException.Reason.INVALID_FILE_TYPE);
        }
    }

    private List<AttachmentResponse> attachImages(Message message, List<File> images) {
        List<AttachmentResponse> attachments = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            MessageFileGroup fileGroup = messageFileGroupRepository.save(MessageFileGroup.builder()
                .message(message)
                .attachment(images.get(i))
                .fileGroupSeq(i + 1)
                .build());
            attachments.add(toAttachmentResponse(fileGroup));
        }
        return attachments;
    }

    private List<HistoryMessage> getHistory(Long conversationId) {
        List<Message> messages = messageRepository.findAllByConversationId(conversationId);
        Map<Long, List<AttachmentResponse>> attachments = findAttachments(messages);

        List<HistoryMessage> history = new ArrayList<>();
        for (Message message : messages) {
            history.add(HistoryMessage.of(message, fileUrls(attachments.getOrDefault(message.getId(), List.of()))));
        }
        return history;
    }

    private Map<Long, List<AttachmentResponse>> findAttachments(List<Message> messages) {
        List<Long> messageIds = messages.stream().map(message -> message.getId()).toList();

        Map<Long, List<AttachmentResponse>> attachments = new HashMap<>();
        for (MessageFileGroup fileGroup : messageFileGroupRepository.findAllByMessageIds(messageIds)) {
            attachments.computeIfAbsent(fileGroup.getMessage().getId(), messageId -> new ArrayList<>())
                .add(toAttachmentResponse(fileGroup));
        }
        return attachments;
    }

    private AttachmentResponse toAttachmentResponse(MessageFileGroup fileGroup) {
        File attachment = fileGroup.getAttachment();
        Duration ttl = Duration.ofSeconds(storageProperties.presignedUrlTtlSeconds());
        String fileUrl = s3StorageService.prepareDownload(attachment.getFileKey(), ttl).url();
        return new AttachmentResponse(attachment.getId(), fileUrl, fileGroup.getFileGroupSeq());
    }

    private static List<String> fileUrls(List<AttachmentResponse> attachments) {
        return attachments.stream().map(attachment -> attachment.fileUrl()).toList();
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
