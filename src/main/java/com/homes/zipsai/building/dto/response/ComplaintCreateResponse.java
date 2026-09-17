package com.homes.zipsai.building.dto.response;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.domain.ComplaintStatus;

public record ComplaintCreateResponse(
    Long complaintId,
    Long conversationId,
    String title,
    ComplaintStatus statusCode,
    String statusLabel,
    String buildingName,
    String roomNo,
    String location,
    OffsetDateTime occurredTime,
    String symptom,
    int attachmentCount,
    LocalDateTime createdAt
) {

    public static ComplaintCreateResponse of(Complaint complaint, ComplaintDetail detail) {
        return new ComplaintCreateResponse(
            complaint.getId(),
            complaint.getConversation().getId(),
            complaint.getTitle(),
            complaint.getStatus(),
            complaint.getStatus().getLabel(),
            complaint.getBuilding().getBuildingName(),
            complaint.getRoomNo(),
            detail.getLocation(),
            detail.getOccurredTime(),
            detail.getSymptom(),
            complaint.getAttachment() == null ? 0 : 1,
            complaint.getCreatedAt()
        );
    }
}
