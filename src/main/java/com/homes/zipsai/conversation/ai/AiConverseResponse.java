package com.homes.zipsai.conversation.ai;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiConverseResponse(String code, String traceId, Data data) {

    public static final String SUCCESS_CODE = "ai_response_success";

    private static final Logger LOGGER = LoggerFactory.getLogger(AiConverseResponse.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

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
    public record DraftPatch(String location, String symptom, String occurredAt) {

        AiComplaintDraft toDraft() {
            return new AiComplaintDraft(location, symptom, parseOccurredAt(occurredAt));
        }

        // AI는 오프셋을 붙이기도 하고 빼기도 한다. 계약상 기준 시간대가 Asia/Seoul이므로 없으면 KST로 읽는다.
        // 시각은 선택 값이라 읽지 못해도 대화를 중단하지 않는다.
        private static OffsetDateTime parseOccurredAt(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return OffsetDateTime.parse(value);
            } catch (DateTimeParseException withoutOffset) {
                return toKst(value);
            }
        }

        private static OffsetDateTime toKst(String value) {
            try {
                return LocalDateTime.parse(value).atZone(KST).toOffsetDateTime();
            } catch (DateTimeParseException unreadable) {
                LOGGER.warn("AI가 보낸 발생 시각을 읽지 못해 비워 둔다. occurredAt={}", value);
                return null;
            }
        }
    }

    public record QaCardDraft(String question) {
    }
}
