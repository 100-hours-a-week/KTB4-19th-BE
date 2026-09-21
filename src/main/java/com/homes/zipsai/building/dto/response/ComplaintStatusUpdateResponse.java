package com.homes.zipsai.building.dto.response;

import java.time.LocalDateTime;

import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintStatus;

public record ComplaintStatusUpdateResponse(
    Long complaintId,
    ComplaintStatus statusCode,
    String statusLabel,
    LocalDateTime resolvedAt,
    LocalDateTime updatedAt
) {

    public static ComplaintStatusUpdateResponse from(Complaint complaint) {
        return new ComplaintStatusUpdateResponse(
            complaint.getId(),
            complaint.getStatus(),
            complaint.getStatus().getLabel(),
            complaint.getResolvedAt(),
            complaint.getUpdatedAt()
        );
    }
}
