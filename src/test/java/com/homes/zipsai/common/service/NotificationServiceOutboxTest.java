package com.homes.zipsai.common.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.homes.zipsai.common.domain.UserNotification;
import com.homes.zipsai.common.repository.UserNotificationRepository;
import com.homes.zipsai.migration.domain.OutboxOperation;
import com.homes.zipsai.migration.service.OutboxRecorder;
import com.homes.zipsai.user.service.UserService;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class NotificationServiceOutboxTest {

    private static final Long USER_ID = 1L;
    private static final Long USER_NOTI_ID = 10L;

    @Mock
    UserNotificationRepository userNotificationRepository;

    @Mock
    UserService userService;

    @Mock
    EntityManager entityManager;

    @Mock
    OutboxRecorder outboxRecorder;

    NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(userNotificationRepository, JsonMapper.builder().build(),
            userService, entityManager, outboxRecorder);
    }

    @Test
    @DisplayName("알림을 읽음으로 바꾸면 이전 이벤트로 기록한다")
    void recordsReadNotificationToOutbox() {
        given(userNotificationRepository.findForMarkRead(USER_ID, USER_NOTI_ID))
            .willReturn(Optional.of(mock(UserNotification.class)));
        given(userNotificationRepository.markReadIfUnread(eq(USER_ID), eq(USER_NOTI_ID), any())).willReturn(1);

        notificationService.markNotificationRead(USER_ID, USER_NOTI_ID);

        then(outboxRecorder).should().record(UserNotification.class, USER_NOTI_ID, OutboxOperation.UPDATE);
    }
}
