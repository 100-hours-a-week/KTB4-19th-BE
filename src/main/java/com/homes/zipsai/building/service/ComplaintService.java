package com.homes.zipsai.building.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintContent;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.domain.ComplaintStatus;
import com.homes.zipsai.building.domain.Room;
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
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.conversation.ai.AiComplaintDraft;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.service.ConversationService;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.global.exception.InvalidQueryParameterException;
import com.homes.zipsai.global.exception.NotFoundException;

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

    @Transactional
    public ComplaintCreateResponse createComplaint(Long userId, ComplaintCreateRequest request) {
        Conversation conversation = conversationService.getOwnedConversation(userId, request.conversationId());
        conversation.verifyCanCreateComplaint();
        Room room = residentRoomService.getLivingRoom(userId);

        AiComplaintDraft confirmedDraft = conversation.currentDraft().withEdits(request.toDraftEdits());
        ComplaintContent content = ComplaintContent.from(confirmedDraft);
        Complaint complaint = complaintRepository.save(Complaint.builder()
            .conversation(conversation)
            .user(room.getResident())
            .building(room.getBuilding())
            .attachment(representativeImage(conversation.getId()))
            .title(content.title())
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
        return ComplaintCreateResponse.of(complaint, detail);
    }

    @Transactional(readOnly = true)
    public ComplaintDetailResponse getManagerComplaint(Long managerId, Long complaintId) {
        Complaint complaint = getManagerComplaintEntity(managerId, complaintId);
        ComplaintDetail detail = complaintDetailRepository.findById(complaintId)
            .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.COMPLAINT));
        return ComplaintDetailResponse.from(complaint, detail);
    }

    @Transactional
    public ComplaintStatusUpdateResponse updateManagerComplaintStatus(
            Long managerId,
            Long complaintId,
            ComplaintStatusUpdateRequest request
    ) {
        Complaint complaint = getManagerComplaintEntity(managerId, complaintId);
        complaint.changeStatus(ComplaintStatus.valueOf(request.statusCode()));
        complaintRepository.flush();
        return ComplaintStatusUpdateResponse.from(complaint);
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
        Building building = buildingRepository.findByManager_IdAndDeletedAtIsNull(managerId)
            .orElseThrow(ForbiddenException::new);
        String normalizedKeyword = normalizeKeyword(keyword);
        List<ComplaintStatus> statuses = normalizeStatuses(statusValues);
        Pageable pageable = PageRequest.of(page, size, MANAGER_COMPLAINT_SORT);
        Page<Complaint> complaints = complaintRepository.findManagerComplaints(
            building.getId(), normalizedKeyword, statuses, urgentOnly, Complaint.URGENCY_THRESHOLD, pageable);
        return ComplaintListResponse.from(complaints);
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
        return ResidentComplaintListResponse.from(complaints);
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
        ComplaintDetail detail = complaintDetailRepository.findById(complaintId)
            .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.COMPLAINT));
        return ResidentComplaintDetailResponse.from(complaint, detail);
    }

    private File representativeImage(Long conversationId) {
        return conversationService.findImages(conversationId).stream().findFirst().orElse(null);
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

}
