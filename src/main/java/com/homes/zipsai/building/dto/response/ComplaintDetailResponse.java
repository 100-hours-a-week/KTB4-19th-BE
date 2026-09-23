package com.homes.zipsai.building.dto.response;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Function;

import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.domain.ComplaintStatus;
import com.homes.zipsai.common.domain.File;

public record ComplaintDetailResponse(
        Long complaintId,
        Long conversationId,
        boolean conversationAvailable,
        String buildingName,
        String roomNo,
        String title,
        ComplaintStatus statusCode,
        String statusLabel,
        int urgency,
        boolean isUrgent,
        String location,
        OffsetDateTime occurredTime,
        String symptom,
        String aiSummary,
        int attachmentCount,
        List<AttachmentItem> attachments,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt
) {

    public static ComplaintDetailResponse from(Complaint complaint, ComplaintDetail detail, Function<File, String> fileUrl) {
        List<AttachmentItem> attachments = toAttachments(complaint.getAttachment(), fileUrl);
        return new ComplaintDetailResponse(
            complaint.getId(),
            complaint.getConversation().getId(),
            complaint.getConversation().getDeletedAt() == null,
            complaint.getBuilding().getBuildingName(),
            complaint.getRoomNo(),
            complaint.getTitle(),
            complaint.getStatus(),
            complaint.getStatus().getLabel(),
            complaint.getUrgency(),
            complaint.getUrgency() >= Complaint.URGENCY_THRESHOLD,
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

    private static List<AttachmentItem> toAttachments(File attachment, Function<File, String> fileUrl) {
        if (attachment == null) {
            return List.of();
        }
        return List.of(AttachmentItem.from(attachment, fileUrl));
    }

    public record AttachmentItem(
            Long attachmentId,
            String fileUrl,
            String originalName,
            String fileType,
            int fileSize,
            int seq
    ) {

        private static AttachmentItem from(File attachment, Function<File, String> fileUrl) {
            return new AttachmentItem(
                attachment.getId(),
                fileUrl.apply(attachment),
                attachment.getOriginalName(),
                attachment.getFileType(),
                attachment.getFileSize(),
                1
            );
        }
    }
}
