package com.homes.zipsai.conversation.ai;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Builder;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AiConverseResponse(String code, String turnId, Data data) {

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

    public DraftPatch complaintDraft() {
        return data.result().complaintDraft();
    }

    public List<ImageObservation> imageObservations() {
        ImageAnalysis imageAnalysis = data.result().imageAnalysis();
        return imageAnalysis == null ? List.of() : imageAnalysis.images();
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
    public record Data(AiRoute route,
                       @JsonFormat(with = JsonFormat.Feature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
                       AiComplaintState nextComplaintState,
                       String reply, Result result) {

        public Data {
            result = result == null ? Result.builder().build() : result;
        }
    }

    @Builder
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Result(DraftPatch complaintDraft, QaCardDraft qaCardDraft, List<String> missingFields,
                         List<Citation> citations, ImageAnalysis imageAnalysis) {

        public Result {
            missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Citation(String sourceType, String sourceId, String title, String snippet, String url) {
    }

    @Builder
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DraftPatch(String location, String symptom, String occurredAt, String issueType,
                             @JsonProperty("attachmentIds") List<Long> attachmentIds,
                             Long representativeAttachmentId) {

        public AiComplaintDraft toDraft() {
            return new AiComplaintDraft(location, symptom, parseOccurredAt(occurredAt));
        }

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

    public record ImageAnalysis(List<ImageObservation> images) {

        public ImageAnalysis {
            images = images == null ? List.of() : List.copyOf(images);
        }
    }

    public record ImageObservation(Long attachmentId, String summary, String ocrText) {
    }
}
