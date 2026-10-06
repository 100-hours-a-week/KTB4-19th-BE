package com.homes.zipsai.building.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Slice;

import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintStatus;
import com.homes.zipsai.common.domain.File;

public record ComplaintListResponse(
        int page,
        int pageSize,
        boolean hasNext,
        List<ComplaintItem> complaints
) {

    public static ComplaintListResponse from(Slice<Complaint> slice, Function<File, String> fileUrl) {
        return new ComplaintListResponse(
            slice.getNumber(),
            slice.getSize(),
            slice.hasNext(),
            slice.getContent().stream().map(complaint -> ComplaintItem.from(complaint, fileUrl)).toList()
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

        private static ComplaintItem from(Complaint complaint, Function<File, String> fileUrl) {
            return new ComplaintItem(
                complaint.getId(),
                complaint.getBuilding().getBuildingName(),
                complaint.getRoomNo(),
                complaint.getTitle(),
                complaint.getStatus(),
                complaint.getStatus().getLabel(),
                complaint.getUrgency(),
                complaint.getUrgency() >= Complaint.URGENCY_THRESHOLD,
                fileUrl.apply(complaint.getAttachment()),
                complaint.getCreatedAt()
            );
        }
    }
}
