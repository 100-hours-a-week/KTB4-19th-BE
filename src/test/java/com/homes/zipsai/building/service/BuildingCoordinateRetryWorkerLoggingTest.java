package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.homes.zipsai.building.client.KakaoAddressClient;
import com.homes.zipsai.building.client.KakaoAddressErrorType;
import com.homes.zipsai.building.client.KakaoAddressResult;
import com.homes.zipsai.building.service.BuildingCoordinateRetryClaimService.Claim;
import com.homes.zipsai.building.service.BuildingCoordinateRetryWorker.Outcome;
import com.homes.zipsai.global.logging.StructuredLogger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class BuildingCoordinateRetryWorkerLoggingTest {

    private static final String ADDRESS = "서울 테스트로 1";
    private static final Claim CLAIM = new Claim(17L, 2, ADDRESS, (short) 1);
    private final BuildingCoordinateRetryClaimService buildingCoordinateRetryClaimService =
        mock(BuildingCoordinateRetryClaimService.class);
    private final KakaoAddressClient kakaoAddressClient = mock(KakaoAddressClient.class);
    private final ObjectMapper objectMapper = spy(new ObjectMapper());
    private final Logger logger = (Logger) LoggerFactory.getLogger("structured-events");
    private final ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
    private final ApplicationContextRunner applicationContextRunner = new ApplicationContextRunner()
        .withUserConfiguration(BuildingCoordinateRetryWorker.class)
        .withBean(BuildingCoordinateRetryClaimService.class, () -> buildingCoordinateRetryClaimService)
        .withBean(KakaoAddressClient.class, () -> kakaoAddressClient)
        .withBean(StructuredLogger.class, () -> new StructuredLogger(objectMapper));

    @BeforeEach
    void setUp() {
        MDC.clear();
        listAppender.start();
        logger.addAppender(listAppender);
        when(buildingCoordinateRetryClaimService.claimNext()).thenReturn(Optional.of(CLAIM));
        when(buildingCoordinateRetryClaimService.prepare(any())).thenReturn(true);
        when(buildingCoordinateRetryClaimService.complete(any(), any())).thenReturn(true);
        when(buildingCoordinateRetryClaimService.retry(any(), anyString(), anyShort())).thenReturn(true);
        when(buildingCoordinateRetryClaimService.fail(any(), anyString(), anyString(), anyShort())).thenReturn(true);
        when(kakaoAddressClient.findCoordinates(ADDRESS)).thenReturn(new KakaoAddressResult.Success(
            new GeometryFactory().createPoint(new Coordinate(127, 37))));
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(listAppender);
        listAppender.stop();
        MDC.clear();
    }

    @ParameterizedTest
    @MethodSource("results")
    @DisplayName("저장한 좌표 작업 결과는 공통 JSON 로그에 건물과 선점 버전 및 오류와 횟수를 기록한다")
    void logsCommittedResult(String errorType, short count, String status, short expectedCount, String reason) {
        Claim claim = new Claim(17L, 2, ADDRESS, count);
        when(buildingCoordinateRetryClaimService.claimNext()).thenReturn(Optional.of(claim));
        if (errorType != null) {
            when(kakaoAddressClient.findCoordinates(ADDRESS)).thenReturn(
                new KakaoAddressResult.Failure(KakaoAddressErrorType.valueOf(errorType)));
        }
        applicationContextRunner.run(context -> {
            context.getBean(BuildingCoordinateRetryWorker.class).executeNext();

            assertThat(listAppender.list).hasSize(1);

            JsonNode event = event();

            assertThat(event.path("event").asText()).isEqualTo("building_coordinate_retry_result");

            assertThat(event.path("service").asText()).isEqualTo("backend");

            assertThat(event.path("timestamp").asText()).isNotBlank();

            assertThat(event.path("trace_id").asText()).isNotBlank();

            assertThat(event.path("building_id").asLong()).isEqualTo(17L);

            assertThat(event.path("claim_version").asLong()).isEqualTo(2);

            assertThat(event.path("status").asText()).isEqualTo(status);

            assertThat(event.path("retry_failure_count").asInt()).isEqualTo(expectedCount);

            if (errorType == null) {
                assertThat(event.path("error_type").isNull()).isTrue();
            } else {
                assertThat(event.path("error_type").asString()).isEqualTo(errorType);
            }

            if (reason == null) {
                assertThat(event.path("failure_reason").isNull()).isTrue();
            } else {
                assertThat(event.path("failure_reason").asString()).isEqualTo(reason);
            }

            assertThat(event.path("outcome").asText()).isEqualTo(errorType == null ? "ok" : "fail");

            assertThat(listAppender.list.getFirst().getLevel()).isEqualTo(errorType == null ? Level.INFO : Level.WARN);

            assertThat(listAppender.list.getFirst().getFormattedMessage()).doesNotContain(ADDRESS, "duration_ms");
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("작업마다 독립 추적값을 사용하고 완료 후 기존 MDC 전체를 복원하거나 비운다")
    void isolatesTraceAndRestoresCallerContext(boolean hasCallerContext) {
        if (hasCallerContext) {
            MDC.setContextMap(Map.of("traceId", "caller", "route", "/caller", "method", "POST", "aiApiMs", "42"));
        }
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        List<String> traces = new ArrayList<>();
        when(kakaoAddressClient.findCoordinates(ADDRESS)).thenAnswer(invocation -> {
            assertThat(MDC.get("traceId")).isNotBlank().isNotEqualTo("caller");

            assertThat(MDC.get("route")).isNull();

            assertThat(MDC.get("method")).isNull();

            assertThat(MDC.get("aiApiMs")).isNull();

            traces.add(MDC.get("traceId"));
            return new KakaoAddressResult.Success(new GeometryFactory().createPoint(new Coordinate(127, 37)));
        });
        applicationContextRunner.run(context -> {
            BuildingCoordinateRetryWorker buildingCoordinateRetryWorker =
                context.getBean(BuildingCoordinateRetryWorker.class);
            buildingCoordinateRetryWorker.executeNext();

            assertThat(MDC.getCopyOfContextMap()).isEqualTo(previousMdc);

            buildingCoordinateRetryWorker.executeNext();

            assertThat(MDC.getCopyOfContextMap()).isEqualTo(previousMdc);

            assertThat(traces).hasSize(2).doesNotHaveDuplicates();

            assertThat(listAppender.list.stream().map(log -> objectMapper.readTree(log.getFormattedMessage())
                .path("trace_id").asText())).containsExactlyElementsOf(traces);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"claim", "complete", "retry"})
    @DisplayName("선점과 결과 저장 예외는 가능한 작업 식별자와 근본 예외 유형을 공통 오류 로그에 남긴다")
    void logsExecutionFailure(String operation) {
        RuntimeException exception = new IllegalStateException("do-not-log", new SQLException("do-not-log"));
        if ("claim".equals(operation)) {
            when(buildingCoordinateRetryClaimService.claimNext()).thenThrow(exception);
        } else if ("complete".equals(operation)) {
            when(buildingCoordinateRetryClaimService.complete(any(), any())).thenThrow(exception);
        } else {
            when(kakaoAddressClient.findCoordinates(ADDRESS)).thenReturn(
                new KakaoAddressResult.Failure(KakaoAddressErrorType.NETWORK_ERROR));
            when(buildingCoordinateRetryClaimService.retry(any(), anyString(), anyShort())).thenThrow(exception);
        }
        applicationContextRunner.run(context -> {
            assertThat(context.getBean(BuildingCoordinateRetryWorker.class).executeNext())
                .isEqualTo(Outcome.STOP_ROUND);

            assertThat(listAppender.list).hasSize(1);

            JsonNode event = event();

            assertThat(event.path("event").asText()).isEqualTo("building_coordinate_retry_error");

            assertThat(event.path("trace_id").asText()).isNotBlank();

            assertThat(event.path("exception_type").asText()).isEqualTo(IllegalStateException.class.getName());

            assertThat(event.path("root_cause_type").asText()).isEqualTo(SQLException.class.getName());

            assertThat(event.path("building_id").isNull()).isEqualTo("claim".equals(operation));

            assertThat(event.path("claim_version").isNull()).isEqualTo("claim".equals(operation));

            assertThat(event.path("outcome").asText()).isEqualTo("fail");

            assertThat(event.path("level").asText()).isEqualTo("ERROR");

            assertThat(listAppender.list.getFirst().getLevel()).isEqualTo(Level.ERROR);

            assertThat(listAppender.list.getFirst().getFormattedMessage()).doesNotContain("do-not-log");

            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        });
    }

    @Test
    @DisplayName("만료된 선점 결과가 저장되지 않으면 저장 완료 로그를 남기지 않는다")
    void skipsResultLogWhenOwnershipIsLost() {
        when(buildingCoordinateRetryClaimService.complete(any(), any())).thenReturn(false);
        applicationContextRunner.run(context -> {
            assertThat(context.getBean(BuildingCoordinateRetryWorker.class).executeNext()).isEqualTo(Outcome.CONTINUE);

            assertThat(listAppender.list).isEmpty();

            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        });
    }

    @Test
    @DisplayName("처리할 작업이 없으면 작업 결과 로그를 남기지 않고 기존 추적 정보를 복원한다")
    void skipsLogWhenNoWorkExists() {
        when(buildingCoordinateRetryClaimService.claimNext()).thenReturn(Optional.empty());
        MDC.put("traceId", "caller");
        applicationContextRunner.run(context -> {
            assertThat(context.getBean(BuildingCoordinateRetryWorker.class).executeNext()).isEqualTo(Outcome.NO_WORK);

            assertThat(listAppender.list).isEmpty();

            assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("traceId", "caller"));
        });
    }

    @Test
    @DisplayName("워커가 비정상 종료되어도 호출자의 MDC를 복원한다")
    void restoresMdcAfterWorkerAborts() {
        when(kakaoAddressClient.findCoordinates(ADDRESS)).thenThrow(new AssertionError("worker aborted"));
        MDC.put("traceId", "caller");
        applicationContextRunner.run(context -> {
            assertThatThrownBy(() -> context.getBean(BuildingCoordinateRetryWorker.class).executeNext())
                .isInstanceOf(AssertionError.class);

            assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("traceId", "caller"));
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("공통 로그 직렬화 실패는 저장된 작업 결과와 기존 예외 처리 및 MDC 복원을 바꾸지 않는다")
    void isolatesLoggingFailure(boolean storageFails) {
        doThrow(new IllegalStateException("logger failed")).when(objectMapper).writeValueAsString(any());
        if (storageFails) {
            when(buildingCoordinateRetryClaimService.complete(any(), any()))
                .thenThrow(new IllegalStateException("storage failed"));
        }
        applicationContextRunner.run(context -> {
            assertThat(context.getBean(BuildingCoordinateRetryWorker.class).executeNext())
                .isEqualTo(storageFails ? Outcome.STOP_ROUND : Outcome.CONTINUE);

            verify(buildingCoordinateRetryClaimService).complete(any(), any());

            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        });
    }

    private JsonNode event() {
        return objectMapper.readTree(listAppender.list.getFirst().getFormattedMessage());
    }

    private static Stream<Arguments> results() {
        return Stream.of(
            Arguments.of(null, (short) 1, "COMPLETED", (short) 1, null),
            Arguments.of("KAKAO_SERVER_ERROR", (short) 1, "PENDING", (short) 2, null),
            Arguments.of("NETWORK_ERROR", (short) 2, "FAILED", (short) 3, "RETRY_EXHAUSTED"),
            Arguments.of("AUTHENTICATION_ERROR", (short) 2, "PENDING", (short) 2, null),
            Arguments.of("INVALID_RESPONSE", (short) 2, "PENDING", (short) 2, null),
            Arguments.of("ADDRESS_NOT_FOUND", (short) 1, "FAILED", (short) 1, "ADDRESS_NOT_FOUND"));
    }
}
