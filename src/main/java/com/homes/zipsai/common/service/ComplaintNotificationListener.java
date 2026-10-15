package com.homes.zipsai.common.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.homes.zipsai.common.domain.UserNotification;
import com.homes.zipsai.common.event.ComplaintNotificationEvent;
import com.homes.zipsai.common.event.NotificationEvent;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ComplaintNotificationListener {

    private final ComplaintNotificationStorageService complaintNotificationStorageService;
    private final NotificationEventPublisher notificationEventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onComplaintNotification(ComplaintNotificationEvent event) {
        try {
            UserNotification userNotification = complaintNotificationStorageService.store(event);
            if (userNotification != null) {
                notificationEventPublisher.publish(new NotificationEvent(event.content().type(),
                    userNotification.getUser().getId(), userNotification.getId()));
            }
        } catch (RuntimeException ignored) {

        }
    }
}
