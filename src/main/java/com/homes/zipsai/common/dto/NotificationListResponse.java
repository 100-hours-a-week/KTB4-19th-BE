package com.homes.zipsai.common.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

import com.homes.zipsai.common.domain.UserNotification;
import com.homes.zipsai.global.util.TimeUtils;

import tools.jackson.databind.JsonNode;

public record NotificationListResponse(long totalCount, int page, int pageSize, boolean hasNext,
                                       List<NotificationItem> notifications) {

    public static NotificationListResponse from(Page<UserNotification> page, Function<String, JsonNode> parseContent) {
        return new NotificationListResponse(
            page.getTotalElements(),
            page.getNumber(),
            page.getSize(),
            page.hasNext(),
            page.getContent().stream().map(row -> NotificationItem.from(row, parseContent)).toList()
        );
    }

    public record NotificationItem(Long userNotiId, Long notificationId, JsonNode content,
                                   OffsetDateTime readAt, OffsetDateTime createdAt) {

        private static NotificationItem from(UserNotification notification, Function<String, JsonNode> parseContent) {
            return new NotificationItem(
                notification.getId(),
                notification.getNotification().getId(),
                parseContent.apply(notification.getNotification().getContent()),
                TimeUtils.toOffsetDateTime(notification.getReadAt()),
                TimeUtils.toOffsetDateTime(notification.getCreatedAt())
            );
        }
    }
}
