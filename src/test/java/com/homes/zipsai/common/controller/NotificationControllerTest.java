package com.homes.zipsai.common.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.homes.zipsai.common.service.NotificationService;
import com.homes.zipsai.common.service.SseService;
import com.homes.zipsai.global.security.AuthPrincipal;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    private static final long USER_ID = 1L;

    @Mock
    NotificationService notificationService;

    @Mock
    SseService sseService;

    NotificationController notificationController;

    @BeforeEach
    void setUp() {
        notificationController = new NotificationController(notificationService, sseService);
    }

    @Test
    @DisplayName("SSE 연결 응답은 프록시가 버퍼링하지 않도록 헤더를 붙인다")
    void disablesProxyBufferingOnStream() {
        SseEmitter emitter = new SseEmitter();
        given(sseService.subscribe(USER_ID)).willReturn(emitter);

        ResponseEntity<SseEmitter> response = notificationController.subscribe(new AuthPrincipal(USER_ID));

        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
        assertThat(response.getBody()).isSameAs(emitter);
    }
}
