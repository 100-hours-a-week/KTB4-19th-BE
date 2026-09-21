package com.homes.zipsai.conversation.ai;

import java.time.OffsetDateTime;
import java.util.List;

import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.SenderType;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiConverseRequest(
    Long buildingId,
    String roomNo,
    String residentId,
    String conversationId,
    String traceId,
    AiRoute currentRoute,
    AiComplaintState currentComplaintState,
    MessagePayload message,
    List<HistoryMessage> conversationHistory,
    ComplaintDraftPayload complaintDraft
) {

    public static AiConverseRequest of(Room room, Conversation conversation, Message residentMessage,
                                       List<Message> history) {
        return new AiConverseRequest(
            room.getBuilding().getId(),
            room.getRoomNo(),
            String.valueOf(room.getResident().getId()),
            String.valueOf(conversation.getId()),
            residentMessage.getTraceId(),
            conversation.getCurrentRoute(),
            conversation.getComplaintState(),
            new MessagePayload(String.valueOf(residentMessage.getId()), residentMessage.getContent(), List.of()),
            history.stream().map(HistoryMessage::from).toList(),
            ComplaintDraftPayload.from(conversation.currentDraft()));
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MessagePayload(String messageId, String text, List<String> imageUrls) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record HistoryMessage(String messageId, AiTurnRole role, String text, List<String> imageUrls) {

        static HistoryMessage from(Message message) {
            AiTurnRole role = message.getSenderType() == SenderType.RESIDENT ? AiTurnRole.USER : AiTurnRole.ASSISTANT;
            return new HistoryMessage(String.valueOf(message.getId()), role, message.getContent(), List.of());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ComplaintDraftPayload(String location, String symptom, OffsetDateTime occurredAt,
                                        List<String> imageUrls) {

        static ComplaintDraftPayload from(AiComplaintDraft draft) {
            if (draft.isEmpty()) {
                return null;
            }
            return new ComplaintDraftPayload(draft.location(), draft.symptom(), draft.occurredAt(), List.of());
        }

        public AiComplaintDraft toDraft() {
            return new AiComplaintDraft(location, symptom, occurredAt);
        }
    }
}
