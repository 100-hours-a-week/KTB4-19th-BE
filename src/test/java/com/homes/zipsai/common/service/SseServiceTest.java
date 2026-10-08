package com.homes.zipsai.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.homes.zipsai.common.config.SseProperties;
import com.homes.zipsai.global.config.SchedulingConfig;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.user.service.UserService;

@DisplayName("SSE 연결 수명과 단일 전송")
class SseServiceTest {

    private final Map<SseEmitter, Integer> attempts = new HashMap<>();
    private final Map<SseEmitter, Integer> heartbeats = new HashMap<>();
    private final Map<SseEmitter, Boolean> failures = new HashMap<>();
    private final Map<SseEmitter, Runnable> completions = new HashMap<>();
    private final Map<SseEmitter, Runnable> timeouts = new HashMap<>();
    private final Map<SseEmitter, Long> timeoutMillis = new HashMap<>();
    private final UserService users = mock(UserService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private MockedConstruction<SseEmitter> emitters;
    private SseService service;

    @BeforeEach
    void setUp() {
        when(scheduler.getClock()).thenReturn(Clock.systemUTC());
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class)))
            .thenReturn(mock(ScheduledFuture.class));
        emitters = mockConstruction(SseEmitter.class, (emitter, context) -> {
            timeoutMillis.put(emitter, (Long) context.arguments().get(0));
            doAnswer(invocation -> {
                Set<DataWithMediaType> event = invocation.getArgument(0);
                boolean delivery = event.stream()
                    .anyMatch(part -> part.getData().toString().contains("event:test"));
                if (event.stream().anyMatch(part -> part.getData().toString().contains(":heartbeat"))) {
                    heartbeats.merge(emitter, 1, Integer::sum);
                }
                if (delivery) {
                    attempts.merge(emitter, 1, Integer::sum);
                    if (failures.getOrDefault(emitter, false)) {
                        throw new IOException("test connection closed");
                    }
                }
                return null;
            }).when(emitter).send(anySet());
            doAnswer(invocation -> {
                completions.put(emitter, invocation.getArgument(0));
                return null;
            }).when(emitter).onCompletion(any(Runnable.class));
            doAnswer(invocation -> {
                timeouts.put(emitter, invocation.getArgument(0));
                return null;
            }).when(emitter).onTimeout(any(Runnable.class));
        });
        service = new SseService(users,
            new SseProperties(Duration.ofSeconds(30), Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("연결 수와 무관하게 설정한 간격의 공통 heartbeat 작업 하나로 모든 연결에 전송한다")
    void sharesConfiguredHeartbeatAcrossConnections() {
        new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class)
            .withPropertyValues("app.sse.heartbeat=100ms")
            .withBean("taskScheduler", TaskScheduler.class, () -> scheduler)
            .withBean(UserService.class, () -> users)
            .withBean(SseService.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                SseService stream = context.getBean(SseService.class);
                SseEmitter first = stream.subscribe(1L);
                SseEmitter second = stream.subscribe(2L);
                ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
                verify(scheduler).scheduleWithFixedDelay(
                    heartbeat.capture(), any(Instant.class), eq(Duration.ofMillis(100)));
                verify(scheduler, never()).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
                heartbeat.getValue().run();
                assertThat(heartbeats).containsEntry(first, 1).containsEntry(second, 1);
            });
    }

    @AfterEach
    void tearDown() {
        service.close();
        emitters.close();
    }

    @Test
    @DisplayName("이벤트 전송은 TaskScheduler 주입이나 예약 작업을 사용하지 않는다")
    void deliversWithoutTaskSchedulerDependency() {
        TaskScheduler otherScheduler = mock(TaskScheduler.class);
        new ApplicationContextRunner()
            .withBean("taskScheduler", TaskScheduler.class, () -> scheduler)
            .withBean("otherTaskScheduler", TaskScheduler.class, () -> otherScheduler)
            .withBean(UserService.class, () -> users)
            .withBean(SseProperties.class,
                () -> new SseProperties(Duration.ofSeconds(30), Duration.ofMinutes(5)))
            .withBean(SseService.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                SseService stream = context.getBean(SseService.class);
                SseEmitter emitter = stream.subscribe(1L);
                stream.send(1, SseEmitter.event().name("test").id("11"));
                assertThat(attempts).containsEntry(emitter, 1);
                verifyNoInteractions(scheduler, otherScheduler);
            });
    }

    @Test
    @DisplayName("전송에 실패한 이벤트은 재접속한 연결에 재시도하지 않는다")
    void doesNotRetryFailedDeliveryOnReconnect() {
        SseEmitter first = connect(1);
        failures.put(first, true);
        service.send(1, SseEmitter.event().name("test").id("11"));
        SseEmitter second = connect(1);
        assertThat(attempts).containsEntry(first, 1).doesNotContainKey(second);
        verifyNoInteractions(scheduler);
    }

    @Test
    @DisplayName("연결이 없을 때 발생한 이벤트은 이후 연결에 재전송하지 않는다")
    void doesNotReplayOfflineNotificationOnReconnect() {
        service.send(1, SseEmitter.event().name("test").id("11"));
        SseEmitter emitter = connect(1);
        assertThat(attempts.get(emitter)).isNull();
        verifyNoInteractions(scheduler);
    }

    @Test
    @DisplayName("한 연결의 실패와 무관하게 수신자의 현재 탭에 한 번씩만 전송한다")
    void sendsOnceToAllCurrentRecipientTabs() {
        SseEmitter failed = connect(1);
        failures.put(failed, true);
        SseEmitter successful = connect(1);
        SseEmitter otherUser = connect(2);
        service.send(1, SseEmitter.event().name("test").id("11"));
        SseEmitter reconnect = connect(1);
        assertThat(attempts).containsEntry(failed, 1).containsEntry(successful, 1)
            .doesNotContainKey(otherUser).doesNotContainKey(reconnect);
    }

    @Test
    @DisplayName("같은 이벤트를 여러 연결에 보낼 때 전송 내용을 한 번만 구성한다")
    void buildsEventOnceForAllConnections() {
        connect(1);
        connect(1);
        SseEmitter.SseEventBuilder event = spy(SseEmitter.event().name("test").data("sample"));

        service.send(1, event);

        verify(event).build();
    }

    @Test
    @DisplayName("heartbeat에서 끊긴 연결은 제거하고 같은 사용자의 다른 탭은 유지한다")
    void removesDisconnectedEmitterOnHeartbeat() throws IOException {
        SseEmitter disconnected = connect(1);
        SseEmitter active = connect(1);
        doAnswer(invocation -> { throw new IOException("Broken pipe"); })
            .when(disconnected).send(anySet());

        service.sendHeartbeat();
        service.sendHeartbeat();
        service.send(1, SseEmitter.event().name("test").id("11"));

        verify(disconnected, times(2)).send(anySet());
        verify(disconnected, never()).complete();
        verify(disconnected, never()).completeWithError(any());
        assertThat(heartbeats).containsEntry(active, 2);
        assertThat(attempts).containsEntry(active, 1).doesNotContainKey(disconnected);
    }

    @Test
    @DisplayName("오래된 연결의 종료 콜백은 같은 사용자의 새 연결을 제거하지 않는다")
    void preservesNewConnectionWhenOldCompletionArrives() {
        SseEmitter old = connect(1);
        completions.get(old).run();
        SseEmitter current = connect(1);
        completions.get(old).run();
        service.send(1, SseEmitter.event().name("test").id("11"));
        assertThat(attempts).containsEntry(current, 1).doesNotContainKey(old);
    }

    @Test
    @DisplayName("heartbeat에서 현재 자격을 잃은 사용자는 연결을 종료한다")
    void closesConnectionWhenEligibilityIsLost() {
        SseEmitter emitter = connect(1);
        doAnswer(invocation -> { throw new ForbiddenException(); })
            .when(users).requireEligibleUser(1L);
        service.sendHeartbeat();
        verify(emitter).complete();
        service.send(1, SseEmitter.event().name("test").id("11"));
        assertThat(attempts.get(emitter)).isNull();
    }

    @Test
    @DisplayName("인증된 사용자의 연결은 설정된 timeout까지 유지하고 종료 후 이벤트을 보내지 않는다")
    void closesConnectionAtConfiguredTimeout() {
        SseEmitter emitter = connect(1);
        assertThat(timeoutMillis).containsEntry(emitter, Duration.ofMinutes(5).toMillis());
        verify(emitter, never()).complete();
        service.send(1, SseEmitter.event().name("test").id("10"));
        assertThat(attempts.get(emitter)).isEqualTo(1);
        timeouts.get(emitter).run();
        verify(emitter).complete();
        service.send(1, SseEmitter.event().name("test").id("11"));
        assertThat(attempts.get(emitter)).isEqualTo(1);
    }

    @Test
    @DisplayName("MVC 초기화 전 전송은 SseEmitter의 기본 버퍼에 맡긴다")
    void delegatesEarlyDeliveryToEmitterBuffer() {
        SseEmitter emitter = service.subscribe(1L);
        service.send(1, SseEmitter.event().name("test").id("11"));
        assertThat(attempts.get(emitter)).isEqualTo(1);
    }

    @Test
    @DisplayName("자격 조회 도중 SSE timeout이 지나도 종료 시점 후 이벤트을 보내지 않는다")
    void rechecksTimeoutImmediatelyBeforeSending() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        try (MockedStatic<Instant> instants = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            instants.when(Instant::now).thenAnswer(invocation -> now.get());
            SseEmitter emitter = connect(1);
            doAnswer(invocation -> {
                now.set(now.get().plus(Duration.ofMinutes(5)));
                return null;
            }).when(users).requireEligibleUser(1L);
            service.send(1, SseEmitter.event().name("test").id("11"));
            assertThat(attempts.get(emitter)).isNull();
            verify(emitter).complete();
        }
    }

    private SseEmitter connect(long userId) {
        return service.subscribe(userId);
    }
}
