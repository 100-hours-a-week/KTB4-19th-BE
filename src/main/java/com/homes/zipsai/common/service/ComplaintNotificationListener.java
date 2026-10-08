package com.homes.zipsai.common.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.homes.zipsai.common.event.ComplaintNotificationEvent;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ComplaintNotificationListener {

    private final ComplaintNotificationStorageService complaintNotificationStorageService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onComplaintNotification(ComplaintNotificationEvent event) {
        try {
            complaintNotificationStorageService.store(event);
        } catch (RuntimeException ignored) {

        }
    }
}
