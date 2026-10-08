package com.homes.zipsai.common.domain;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.homes.zipsai.building.domain.ComplaintStatus;
import com.homes.zipsai.global.util.TimeUtils;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComplaintNotificationContent(
        Type type,
        long complaintId,
        long buildingId,
        String title,
        String roomNo,
        ComplaintStatus statusCode,
        OffsetDateTime occurredAt
) {

    public ComplaintNotificationContent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        occurredAt = TimeUtils.toOffsetDateTime(Objects.requireNonNull(occurredAt, "occurredAt"));
        if (type == Type.COMPLAINT_CREATED) {
            Objects.requireNonNull(roomNo, "roomNo");
        } else {
            Objects.requireNonNull(statusCode, "statusCode");
        }
    }

    public enum Type {
        COMPLAINT_CREATED,
        COMPLAINT_STATUS_CHANGED
    }

    public static ComplaintNotificationContent complaintCreated(long complaintId, long buildingId, String title,
                                                               String roomNo, LocalDateTime occurredAt) {
        return new ComplaintNotificationContent(Type.COMPLAINT_CREATED, complaintId, buildingId,
            title, roomNo, null, TimeUtils.toOffsetDateTime(occurredAt));
    }

    public static ComplaintNotificationContent complaintStatusChanged(long complaintId, long buildingId, String title,
                                                                     ComplaintStatus status, LocalDateTime occurredAt) {
        return new ComplaintNotificationContent(Type.COMPLAINT_STATUS_CHANGED, complaintId, buildingId,
            title, null, status, TimeUtils.toOffsetDateTime(occurredAt));
    }
}
