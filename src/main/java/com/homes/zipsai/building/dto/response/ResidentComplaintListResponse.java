package com.homes.zipsai.building.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

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

    public static ResidentComplaintListResponse from(Page<Complaint> page, Function<File, String> fileUrl) {
        return new ResidentComplaintListResponse(
            page.getTotalElements(),
            page.getNumber(),
            page.getSize(),
            page.hasNext(),
            page.getContent().stream().map(complaint -> ComplaintItem.from(complaint, fileUrl)).toList()
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

        private static ComplaintItem from(Complaint complaint, Function<File, String> fileUrl) {
            return new ComplaintItem(
                complaint.getId(),
                complaint.getTitle(),
                complaint.getStatus(),
                complaint.getStatus().getLabel(),
                fileUrl.apply(complaint.getAttachment()),
                complaint.getCreatedAt()
            );
        }
    }
}
