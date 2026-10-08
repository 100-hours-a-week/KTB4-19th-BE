package com.homes.zipsai.common.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.homes.zipsai.common.domain.UserNotification;
import com.homes.zipsai.common.event.ComplaintNotificationEvent;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ComplaintNotificationListener {

    private final ComplaintNotificationStorageService complaintNotificationStorageService;
    private final NotificationStreamService notificationStreamService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onComplaintNotification(ComplaintNotificationEvent event) {
        try {
            UserNotification received = complaintNotificationStorageService.store(event);
            if (received != null) {
                notificationStreamService.sendNotification(received.getUser().getId(), received.getId());
            }
        } catch (RuntimeException ignored) {

        }
    }
}
