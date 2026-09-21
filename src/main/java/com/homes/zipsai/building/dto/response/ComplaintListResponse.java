package com.homes.zipsai.building.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;

import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintStatus;

public record ComplaintListResponse(
        long totalCount,
        int page,
        int pageSize,
        boolean hasNext,
        List<ComplaintItem> complaints
) {

    public static ComplaintListResponse from(Page<Complaint> page) {
        return new ComplaintListResponse(
            page.getTotalElements(),
            page.getNumber(),
            page.getSize(),
            page.hasNext(),
            page.getContent().stream().map(ComplaintItem::from).toList()
        );
    }

    public record ComplaintItem(
            Long complaintId,
            String buildingName,
            String roomNo,
            String title,
            ComplaintStatus statusCode,
            String statusLabel,
            int urgency,
            boolean isUrgent,
            String fileUrl,
            LocalDateTime createdAt
    ) {

        private static ComplaintItem from(Complaint complaint) {
            return new ComplaintItem(
                complaint.getId(),
                complaint.getBuilding().getBuildingName(),
                complaint.getRoomNo(),
                complaint.getTitle(),
                complaint.getStatus(),
                complaint.getStatus().getLabel(),
                complaint.getUrgency(),
                complaint.getUrgency() >= Complaint.URGENCY_THRESHOLD,
                null,
                complaint.getCreatedAt()
            );
        }
    }
}
