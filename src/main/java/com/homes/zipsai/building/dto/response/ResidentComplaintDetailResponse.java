package com.homes.zipsai.building.dto.response;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

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

    public static ResidentComplaintDetailResponse from(Complaint complaint, ComplaintDetail detail, List<File> images,
                                             Function<File, String> fileUrl) {
        List<AttachmentItem> attachments = toAttachments(images, fileUrl);
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
            images.size(),
            attachments,
            complaint.getCreatedAt(),
            complaint.getResolvedAt()
        );
    }

    private static List<AttachmentItem> toAttachments(List<File> images, Function<File, String> fileUrl) {
        List<AttachmentItem> attachments = new ArrayList<>();
        for (File image : images) {
            attachments.add(AttachmentItem.from(image, attachments.size() + 1, fileUrl));
        }
        return attachments;
    }

    public record AttachmentItem(
            Long attachmentId,
            String fileUrl,
            String originalName,
            String fileType,
            int fileSize,
            int seq
    ) {

        private static AttachmentItem from(File attachment, int seq, Function<File, String> fileUrl) {
            return new AttachmentItem(
                attachment.getId(),
                fileUrl.apply(attachment),
                attachment.getOriginalName(),
                attachment.getFileType(),
                attachment.getFileSize(),
                seq
            );
        }
    }
}
