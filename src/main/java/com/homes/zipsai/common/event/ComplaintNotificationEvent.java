package com.homes.zipsai.common.event;

import com.homes.zipsai.common.domain.ComplaintNotificationContent;

public record ComplaintNotificationEvent(ComplaintNotificationContent content, long residentId) {
}
