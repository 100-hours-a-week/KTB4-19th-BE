package com.homes.zipsai.conversation.ai;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.MessageFileGroup;
import com.homes.zipsai.conversation.domain.SenderType;

import lombok.Builder;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiConverseRequest(
    Long buildingId,
    String roomNo,
    String residentId,
    String conversationId,
    String turnId,
    String traceId,
    AiRoute currentRoute,
    AiComplaintState currentComplaintState,
    MessagePayload message,
    List<HistoryMessage> conversationHistory,
    ComplaintDraftPayload complaintDraft
) {

    public static AiConverseRequest of(Room room, Conversation conversation, Message residentMessage,
                                       List<MessageImage> images, List<HistoryMessage> history, String traceId) {
        return new AiConverseRequest(
            room.getBuilding().getId(),
            room.getRoomNo(),
            String.valueOf(room.getResident().getId()),
            String.valueOf(conversation.getId()),
            residentMessage.getTurnId(),
            traceId,
            conversation.getCurrentRoute(),
            conversation.getComplaintState(),
            new MessagePayload(String.valueOf(residentMessage.getId()), residentMessage.getContent(), images),
            history,
            ComplaintDraftPayload.from(conversation));
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MessagePayload(String messageId, String text, List<MessageImage> images) {

        public MessagePayload {
            images = images == null ? List.of() : List.copyOf(images);
        }
    }

    public record MessageImage(Long attachmentId, String url) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record HistoryMessage(String messageId, AiTurnRole role, String text,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) List<HistoryImage> images) {

        public static HistoryMessage of(Message message, List<HistoryImage> images) {
            String messageId = String.valueOf(message.getId());
            if (message.getSenderType() == SenderType.RESIDENT) {
                return new HistoryMessage(messageId, AiTurnRole.USER, message.getContent(), images);
            }
            return new HistoryMessage(messageId, AiTurnRole.ASSISTANT, message.getContent(), null);
        }
    }

    public record HistoryImage(Long attachmentId, String summary, String ocrText) {

        public static HistoryImage from(MessageFileGroup fileGroup) {
            return new HistoryImage(fileGroup.getAttachment().getId(), fileGroup.getSummary(), fileGroup.getOcrText());
        }
    }

    @Builder
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ComplaintDraftPayload(String issueType, String location, String symptom, OffsetDateTime occurredAt,
                                        @JsonProperty("attachmentIds") List<Long> attachmentIds) {

        public ComplaintDraftPayload {
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
        }

        static ComplaintDraftPayload from(Conversation conversation) {
            AiComplaintDraft draft = conversation.currentDraft();
            ComplaintDraftPayload payload = new ComplaintDraftPayload(conversation.getDraftIssueType(),
                draft.location(), draft.symptom(), draft.occurredAt(), conversation.getDraftAttachmentIds());
            if (draft.isEmpty() && payload.issueType() == null && payload.attachmentIds().isEmpty()) {
                return null;
            }
            return payload;
        }

        public AiComplaintDraft toDraft() {
            return new AiComplaintDraft(location, symptom, occurredAt);
        }
    }
}
