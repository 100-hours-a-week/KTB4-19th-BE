package com.homes.zipsai.building.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintContent;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.domain.ComplaintStatus;
import com.homes.zipsai.building.domain.ComplaintType;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.dto.request.ComplaintCommentUpdateRequest;
import com.homes.zipsai.building.dto.request.ComplaintCreateRequest;
import com.homes.zipsai.building.dto.request.ComplaintStatusUpdateRequest;
import com.homes.zipsai.building.dto.response.ComplaintCreateResponse;
import com.homes.zipsai.building.dto.response.ComplaintDetailResponse;
import com.homes.zipsai.building.dto.response.ComplaintListResponse;
import com.homes.zipsai.building.dto.response.ComplaintStatusUpdateResponse;
import com.homes.zipsai.building.dto.response.ResidentComplaintDetailResponse;
import com.homes.zipsai.building.dto.response.ResidentComplaintListResponse;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.ComplaintDetailRepository;
import com.homes.zipsai.building.repository.ComplaintRepository;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.domain.ComplaintNotificationContent;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.common.event.ComplaintNotificationEvent;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.ai.AiComplaintDraft;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.service.ConversationService;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.global.exception.InvalidQueryParameterException;
import com.homes.zipsai.global.exception.NotFoundException;
import com.homes.zipsai.global.util.TimeUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ComplaintService {

    private static final int URGENCY_NOT_EVALUATED = 0;
    private static final Sort MANAGER_COMPLAINT_SORT = Sort.by(
        Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ComplaintRepository complaintRepository;
    private final ComplaintDetailRepository complaintDetailRepository;
    private final BuildingRepository buildingRepository;
    private final ResidentRoomService residentRoomService;
    private final ConversationService conversationService;
    private final S3StorageService s3StorageService;
    private final StorageProperties storageProperties;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public ComplaintCreateResponse createComplaint(Long userId, ComplaintCreateRequest request) {
        Conversation conversation = conversationService.getOwnedConversation(userId, request.conversationId());
        conversation.verifyCanCreateComplaint();
        Room room = residentRoomService.getLivingRoom(userId);

        AiComplaintDraft confirmedDraft = conversation.currentDraft().withEdits(request.toDraftEdits());
        ComplaintContent content = ComplaintContent.from(confirmedDraft);
        ComplaintType type = ComplaintType.from(conversation.getCurrentRoute());
        Complaint complaint = complaintRepository.save(Complaint.builder()
            .conversation(conversation)
            .user(room.getResident())
            .building(room.getBuilding())
            .attachment(selectRepresentativeImage(conversation, type, request.representativeAttachmentId()))
            .title(content.title())
            .type(type)
            .urgency(URGENCY_NOT_EVALUATED)
            .roomNo(room.getRoomNo())
            .build());
        ComplaintDetail detail = complaintDetailRepository.save(ComplaintDetail.builder()
            .complaint(complaint)
            .location(content.location())
            .occurredTime(content.occurredTime())
            .symptom(content.symptom())
            .aiSummary(content.aiSummary())
            .build());
        conversation.markComplaintCreated(content.title(), confirmedDraft);
        applicationEventPublisher.publishEvent(new ComplaintNotificationEvent(
            ComplaintNotificationContent.complaintCreated(
                complaint.getId(), complaint.getBuilding().getId(), complaint.getTitle(), complaint.getRoomNo(),
                TimeUtils.now()),
            complaint.getUser().getId()));
        return ComplaintCreateResponse.of(complaint, detail);
    }

    @Transactional(readOnly = true)
    public ComplaintDetailResponse getManagerComplaint(Long managerId, Long complaintId) {
        Complaint complaint = getManagerComplaintEntity(managerId, complaintId);
        ComplaintDetail detail = getComplaintDetail(complaintId);
        return ComplaintDetailResponse.from(complaint, detail,
            conversationService.findImages(complaint.getConversation().getId()), this::attachmentUrl);
    }

    @Transactional
    public ComplaintStatusUpdateResponse updateManagerComplaintStatus(
            Long managerId,
            Long complaintId,
            ComplaintStatusUpdateRequest request
    ) {
        Complaint complaint = getManagerComplaintEntity(managerId, complaintId);
        if (complaint.changeStatus(ComplaintStatus.valueOf(request.statusCode()))) {
            complaintRepository.flush();
            applicationEventPublisher.publishEvent(new ComplaintNotificationEvent(
                ComplaintNotificationContent.complaintStatusChanged(
                    complaint.getId(), complaint.getBuilding().getId(), complaint.getTitle(), complaint.getStatus(),
                    TimeUtils.now()),
                complaint.getUser().getId()));
        }
        return ComplaintStatusUpdateResponse.from(complaint);
    }

    @Transactional
    public void updateManagerComplaintComment(
            Long managerId,
            Long complaintId,
            ComplaintCommentUpdateRequest request
    ) {
        Complaint complaint = getManagerComplaintEntity(managerId, complaintId);
        complaint.verifyCommentable();
        getComplaintDetail(complaintId).updateComment(request.comment());
    }

    @Transactional
    public void deleteManagerComplaintComment(Long managerId, Long complaintId) {
        getManagerComplaintEntity(managerId, complaintId);
        getComplaintDetail(complaintId).deleteComment();
    }

    @Transactional(readOnly = true)
    public ComplaintListResponse getManagerComplaints(
            Long managerId,
            String keyword,
            List<String> statusValues,
            boolean urgentOnly,
            int page,
            int size
    ) {
        String normalizedKeyword = normalizeKeyword(keyword);
        List<ComplaintStatus> statuses = normalizeStatuses(statusValues);
        Pageable pageable = PageRequest.of(page, size, MANAGER_COMPLAINT_SORT);
        Slice<Complaint> complaints = complaintRepository.findManagerComplaints(
            managerId, normalizedKeyword, statuses, urgentOnly, Complaint.URGENCY_THRESHOLD, pageable);
        if (complaints.isEmpty()) {
            verifyManagesBuilding(managerId);
        }
        return ComplaintListResponse.from(complaints, this::attachmentUrl);
    }

    @Transactional(readOnly = true)
    public ResidentComplaintListResponse getResidentComplaints(
            Long residentId,
            String keyword,
            List<String> statusValues,
            int page,
            int size
    ) {
        residentRoomService.getLivingRoom(residentId);
        Pageable pageable = PageRequest.of(page, size, MANAGER_COMPLAINT_SORT);
        Page<Complaint> complaints = complaintRepository.findResidentComplaints(
            residentId, normalizeKeyword(keyword), normalizeStatuses(statusValues), pageable);
        return ResidentComplaintListResponse.from(complaints, this::attachmentUrl);
    }

    @Transactional(readOnly = true)
    public ResidentComplaintDetailResponse getResidentComplaint(Long residentId, Long complaintId) {
        residentRoomService.getLivingRoom(residentId);
        Complaint complaint = complaintRepository.findByIdAndDeletedAtIsNull(complaintId)
            .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.COMPLAINT));
        if (complaint.getBuilding().getDeletedAt() != null) {
            throw new NotFoundException(NotFoundException.Resource.COMPLAINT);
        }
        if (!complaint.getUser().getId().equals(residentId)) {
            throw new ForbiddenException();
        }
        ComplaintDetail detail = getComplaintDetail(complaintId);
        return ResidentComplaintDetailResponse.from(complaint, detail,
            conversationService.findImages(complaint.getConversation().getId()), this::attachmentUrl);
    }

    private File selectRepresentativeImage(Conversation conversation, ComplaintType type,
                                           Long residentChosenAttachmentId) {
        List<File> images = conversationService.findImages(conversation.getId());
        if (type == ComplaintType.QA && residentChosenAttachmentId != null) {
            return findImage(images, residentChosenAttachmentId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.ATTACHMENT));
        }
        if (type == ComplaintType.QA) {
            return firstImage(images);
        }
        return findImage(images, conversation.getDraftRepresentativeAttachmentId())
            .orElseGet(() -> firstImage(images));
    }

    private Optional<File> findImage(List<File> images, Long attachmentId) {
        return images.stream()
            .filter(image -> image.getId().equals(attachmentId))
            .findFirst();
    }

    private File firstImage(List<File> images) {
        return images.isEmpty() ? null : images.getFirst();
    }

    private String attachmentUrl(File attachment) {
        if (attachment == null) {
            return null;
        }
        Duration ttl = Duration.ofSeconds(storageProperties.presignedUrlTtlSeconds());
        return s3StorageService.prepareDownload(attachment.getFileKey(), ttl).url();
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<ComplaintStatus> normalizeStatuses(List<String> statusValues) {
        if (statusValues == null || statusValues.isEmpty()) {
            return List.of(ComplaintStatus.values());
        }
        List<ComplaintStatus> statuses = new ArrayList<>();
        for (String statusValue : statusValues) {
            for (String value : statusValue.split(",")) {
                try {
                    statuses.add(ComplaintStatus.valueOf(value.trim()));
                } catch (IllegalArgumentException exception) {
                    throw InvalidQueryParameterException.typeMismatch("status", ComplaintStatus.class);
                }
            }
        }
        return statuses.stream().distinct().toList();
    }

    private void verifyManagesBuilding(Long managerId) {
        if (!buildingRepository.existsByManager_IdAndDeletedAtIsNull(managerId)) {
            throw new NotFoundException(NotFoundException.Resource.BUILDING);
        }
    }

    private Complaint getManagerComplaintEntity(Long managerId, Long complaintId) {
        Complaint complaint = complaintRepository.findByIdAndDeletedAtIsNull(complaintId)
            .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.COMPLAINT));
        if (complaint.getBuilding().getDeletedAt() != null) {
            throw new NotFoundException(NotFoundException.Resource.COMPLAINT);
        }
        if (!complaint.getBuilding().getManager().getId().equals(managerId)) {
            throw new ForbiddenException();
        }
        return complaint;
    }

    private ComplaintDetail getComplaintDetail(Long complaintId) {
        return complaintDetailRepository.findById(complaintId)
            .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.COMPLAINT));
    }
}
