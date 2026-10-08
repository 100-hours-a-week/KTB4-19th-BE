package com.homes.zipsai.common.dto;

import java.time.OffsetDateTime;

import com.homes.zipsai.common.domain.UserNotification;
import com.homes.zipsai.global.util.TimeUtils;

public record NotificationReadResponse(Long userNotiId, OffsetDateTime readAt, long unreadCount) {

    public static NotificationReadResponse from(UserNotification notification, long unreadCount) {
        return new NotificationReadResponse(
            notification.getId(),
            TimeUtils.toOffsetDateTime(notification.getReadAt()),
            unreadCount
        );
    }
}
