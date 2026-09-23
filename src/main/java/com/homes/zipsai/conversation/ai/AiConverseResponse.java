package com.homes.zipsai.conversation.ai;

import java.time.OffsetDateTime;
import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiConverseResponse(String code, String traceId, Data data) {

    public static final String SUCCESS_CODE = "ai_response_success";

    public boolean isSuccess() {
        return SUCCESS_CODE.equals(code) && data != null;
    }

    public AiRoute route() {
        return data.route();
    }

    public AiComplaintState nextComplaintState() {
        return data.nextComplaintState();
    }

    public String reply() {
        return data.reply();
    }

    public AiComplaintDraft draftPatch() {
        DraftPatch patch = data.result().complaintDraft();
        return patch == null ? null : patch.toDraft();
    }

    public String qaCardQuestion() {
        QaCardDraft qaCardDraft = data.result().qaCardDraft();
        return qaCardDraft == null ? null : qaCardDraft.question();
    }

    public boolean isConversationComplete() {
        return switch (data.route()) {
            case COMPLAINT -> data.result().missingFields().isEmpty();
            case KNOWLEDGE -> data.result().citations().isEmpty();
            case CLARIFY -> false;
        };
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Data(AiRoute route, AiComplaintState nextComplaintState, String reply, Result result) {

        public Data {
            result = result == null ? new Result(null, null, List.of(), List.of()) : result;
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Result(DraftPatch complaintDraft, QaCardDraft qaCardDraft, List<String> missingFields,
                         List<Citation> citations) {

        public Result {
            missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Citation(String sourceType, String sourceId, String title, String snippet, String url) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DraftPatch(String location, String symptom, OffsetDateTime occurredAt) {

        AiComplaintDraft toDraft() {
            return new AiComplaintDraft(location, symptom, occurredAt);
        }
    }

    public record QaCardDraft(String question) {
    }
}
