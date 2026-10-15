package com.homes.zipsai.common.event;

import com.homes.zipsai.common.domain.ComplaintNotificationContent.Type;
import com.homes.zipsai.global.exception.InvalidPubSubFieldException;
import com.homes.zipsai.global.exception.InvalidPubSubFieldException.Reason;

public record NotificationEvent(Type eventType, long userId, long resourceId) {

    public NotificationEvent {
        if (eventType == null) {
            throw new InvalidPubSubFieldException("eventType", Reason.REQUIRED_VALUE);
        }
        if (userId <= 0) {
            throw new InvalidPubSubFieldException("userId", Reason.INVALID_ID);
        }
        if (resourceId <= 0) {
            throw new InvalidPubSubFieldException("resourceId", Reason.INVALID_ID);
        }
    }
}
