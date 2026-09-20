package com.homes.zipsai.building.dto.response;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.domain.ComplaintStatus;
import com.homes.zipsai.common.domain.File;

public record ResidentComplaintDetailResponse(
        Long complaintId,
        Long conversationId,
        boolean conversationAvailable,
        String buildingName,
        String roomNo,
        String title,
        ComplaintStatus statusCode,
        String statusLabel,
        String location,
        OffsetDateTime occurredTime,
        String symptom,
        String aiSummary,
        int attachmentCount,
        List<AttachmentItem> attachments,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt
) {

    public static ResidentComplaintDetailResponse from(Complaint complaint, ComplaintDetail detail) {
        List<AttachmentItem> attachments = toAttachments(complaint.getAttachment());
        return new ResidentComplaintDetailResponse(
            complaint.getId(),
            complaint.getConversation().getId(),
            complaint.getConversation().getDeletedAt() == null,
            complaint.getBuilding().getBuildingName(),
            complaint.getRoomNo(),
            complaint.getTitle(),
            complaint.getStatus(),
            complaint.getStatus().getLabel(),
            detail.getLocation(),
            detail.getOccurredTime(),
            detail.getSymptom(),
            detail.getAiSummary(),
            attachments.size(),
            attachments,
            complaint.getCreatedAt(),
            complaint.getResolvedAt()
        );
    }

    private static List<AttachmentItem> toAttachments(File attachment) {
        return attachment == null ? List.of() : List.of(AttachmentItem.from(attachment));
    }

    public record AttachmentItem(
            Long attachmentId,
            String fileUrl,
            String originalName,
            String fileType,
            int fileSize,
            int seq
    ) {

        private static AttachmentItem from(File attachment) {
            return new AttachmentItem(
                attachment.getId(),
                ResidentComplaintListResponse.fileUrl(attachment),
                attachment.getOriginalName(),
                attachment.getFileType(),
                attachment.getFileSize(),
                1
            );
        }
    }
}
