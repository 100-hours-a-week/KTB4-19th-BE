package com.homes.zipsai.building.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintContent;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.dto.request.ComplaintCreateRequest;
import com.homes.zipsai.building.dto.response.ComplaintCreateResponse;
import com.homes.zipsai.building.repository.ComplaintDetailRepository;
import com.homes.zipsai.building.repository.ComplaintRepository;
import com.homes.zipsai.conversation.ai.AiComplaintDraft;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.service.ConversationService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ComplaintService {

    private static final int URGENCY_NOT_EVALUATED = 0;

    private final ComplaintRepository complaintRepository;
    private final ComplaintDetailRepository complaintDetailRepository;
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
}
