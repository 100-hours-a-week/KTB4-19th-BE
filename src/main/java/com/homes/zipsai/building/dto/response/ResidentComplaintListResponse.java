package com.homes.zipsai.building.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;

import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintStatus;
import com.homes.zipsai.common.domain.File;

public record ResidentComplaintListResponse(
        long totalCount,
        int page,
        int pageSize,
        boolean hasNext,
        List<ComplaintItem> complaints
) {

    public static ResidentComplaintListResponse from(Page<Complaint> page) {
        return new ResidentComplaintListResponse(
            page.getTotalElements(),
            page.getNumber(),
            page.getSize(),
            page.hasNext(),
            page.getContent().stream().map(ComplaintItem::from).toList()
        );
    }

    public record ComplaintItem(
            Long complaintId,
            String title,
            ComplaintStatus statusCode,
            String statusLabel,
            String fileUrl,
            LocalDateTime createdAt
    ) {

        private static ComplaintItem from(Complaint complaint) {
            return new ComplaintItem(
                complaint.getId(),
                complaint.getTitle(),
                complaint.getStatus(),
                complaint.getStatus().getLabel(),
                ResidentComplaintListResponse.fileUrl(complaint.getAttachment()),
                complaint.getCreatedAt()
            );
        }
    }

    static String fileUrl(File file) {
        return file == null ? null : "/api/v1/files/" + file.getId();
    }
}
