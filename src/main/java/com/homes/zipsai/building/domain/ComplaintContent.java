package com.homes.zipsai.building.domain;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.homes.zipsai.conversation.ai.AiComplaintDraft;
import com.homes.zipsai.global.util.TextUtils;

public record ComplaintContent(String title, String aiSummary, String location, OffsetDateTime occurredTime,
                               String symptom) {

    private static final String UNKNOWN = "미상";
    private static final String DEFAULT_TITLE = "민원 접수";
    private static final int TITLE_MAX_LENGTH = 50;
    private static final int SUMMARY_MAX_LENGTH = 200;
    private static final DateTimeFormatter SUMMARY_TIME_FORMAT =
        DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    public static ComplaintContent from(AiComplaintDraft draft) {
        String location = draft.location();
        String symptom = draft.symptom();
        OffsetDateTime occurredAt = draft.occurredAt();
        return new ComplaintContent(title(location, symptom), summary(location, occurredAt, symptom),
            orUnknown(location), occurredAt, orUnknown(symptom));
    }

    private static String title(String location, String symptom) {
        String source = symptom != null ? symptom : location;
        return source == null ? DEFAULT_TITLE : TextUtils.truncate(source, TITLE_MAX_LENGTH);
    }

    private static String summary(String location, OffsetDateTime occurredAt, String symptom) {
        String occurredTime = occurredAt == null ? null : SUMMARY_TIME_FORMAT.format(occurredAt);
        String summary = Stream.of(labeled("위치", location), labeled("시점", occurredTime), labeled("증상", symptom))
            .filter(Objects::nonNull)
            .collect(Collectors.joining(" / "));
        return summary.isEmpty() ? DEFAULT_TITLE : TextUtils.truncate(summary, SUMMARY_MAX_LENGTH);
    }

    private static String labeled(String label, String value) {
        return value == null ? null : label + ": " + value;
    }

    private static String orUnknown(String value) {
        return value == null ? UNKNOWN : value;
    }
}
