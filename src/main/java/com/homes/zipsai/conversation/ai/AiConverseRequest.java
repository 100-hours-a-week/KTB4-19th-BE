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
    ResidentContext residentContext,
    Long conversationId,
    String traceId,
    AiRoute currentRoute,
    AiConversationState conversationState,
    MessagePayload message,
    List<HistoryMessage> conversationHistory,
    ComplaintDraftPayload complaintDraft
) {

    public static AiConverseRequest of(Room room, Conversation conversation, Message residentMessage,
                                       List<Message> history) {
        AiComplaintDraft draft = conversation.currentDraft();
        ComplaintDraftPayload draftPayload = null;
        if (!draft.isEmpty()) {
            draftPayload = new ComplaintDraftPayload(draft.location(), draft.symptom(), draft.occurredAt(), List.of());
        }
        return new AiConverseRequest(
            room.getBuilding().getId(),
            new ResidentContext(room.getId(), room.getResident().getId()),
            conversation.getId(),
            residentMessage.getTraceId(),
            conversation.getCurrentRoute(),
            conversation.getConversationState(),
            new MessagePayload(residentMessage.getId(), residentMessage.getContent(), List.of()),
            history.stream().map(HistoryMessage::from).toList(),
            draftPayload);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ResidentContext(Long roomId, Long residentId) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MessagePayload(Long messageId, String text, List<String> imageUrls) {
    }

    public record HistoryMessage(AiTurnRole role, String content) {

        static HistoryMessage from(Message message) {
            AiTurnRole role = message.getSenderType() == SenderType.RESIDENT ? AiTurnRole.USER : AiTurnRole.ASSISTANT;
            return new HistoryMessage(role, message.getContent());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ComplaintDraftPayload(String location, String symptom, OffsetDateTime occurredAt,
                                        List<String> imageUrls) {

        public AiComplaintDraft toDraft() {
            return new AiComplaintDraft(location, symptom, occurredAt);
        }
    }
}
