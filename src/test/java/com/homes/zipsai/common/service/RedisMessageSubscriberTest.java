package com.homes.zipsai.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.connection.DefaultMessage;

import com.homes.zipsai.global.logging.StructuredLogger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Redis 공통 메시지 수신")
class RedisMessageSubscriberTest {

    private RedisMessageSubscriber redisMessageSubscriber;
    private ObjectMapper objectMapper;
    private Logger logger;
    private ListAppender<ILoggingEvent> logAppender;
    private Map<String, String> previousMdc;

    @BeforeEach
    void setUp() {
        previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        objectMapper = new ObjectMapper();
        redisMessageSubscriber = new RedisMessageSubscriber(objectMapper, new StructuredLogger(objectMapper));
        logger = (Logger) LoggerFactory.getLogger("structured-events");
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
        logAppender.stop();
        if (previousMdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousMdc);
        }
    }

    @Test
    @DisplayName("연속 메시지마다 해당 추적값만 적용해 데이터를 전달하고 이전 MDC 전체를 복원한다")
    void scopesEachMessageAndRestoresMdc() {
        Map<String, String> workerContext = Map.of("traceId", "previous-worker", "route", "/previous", "method", "GET");
        MDC.setContextMap(workerContext);
        List<Map<String, String>> observedContexts = new ArrayList<>();
        List<String> observedPayloads = new ArrayList<>();
        List<String> traceIds = List.of("trace-id", "trace-id-2");
        for (String traceId : traceIds) {
            redisMessageSubscriber.receive(message("""
                {"trace_id":"%s","payload":"test-value"}
                """.formatted(traceId)), "test:events", String.class, payload -> {
                    observedPayloads.add(payload);
                    observedContexts.add(MDC.getCopyOfContextMap());
                    MDC.put(StructuredLogger.ERROR_CODE, "test-only-listener-value");
                });

            assertThat(MDC.getCopyOfContextMap()).isEqualTo(workerContext);
        }

        assertThat(observedPayloads).containsExactly("test-value", "test-value");

        assertThat(observedContexts)
            .containsExactly(Map.of("traceId", "trace-id"), Map.of("traceId", "trace-id-2"));

        assertThat(logAppender.list).hasSize(2);

        for (int index = 0; index < traceIds.size(); index++) {
            ILoggingEvent logEvent = logAppender.list.get(index);

            assertThat(logEvent.getLevel()).isEqualTo(Level.INFO);

            JsonNode log = objectMapper.readTree(logEvent.getFormattedMessage());

            assertThat(log.path("operation").asText()).isEqualTo("receive");

            assertThat(log.path("outcome").asText()).isEqualTo("ok");

            assertThat(log.path("trace_id").asText()).isEqualTo(traceIds.get(index));

            assertThat(log.path("route").isNull()).isTrue();

            assertThat(log.path("channel").asText()).isEqualTo("test:events");
        }
    }

    @Test
    @DisplayName("처리 예외는 메시지 추적값으로 한 번만 기록하고 이전 MDC를 복원한다")
    void logsDispatchFailureBeforeRestoringMdc() {
        Map<String, String> workerContext = Map.of("traceId", "previous-worker", "route", "/previous");
        MDC.setContextMap(workerContext);

        assertThatCode(() -> redisMessageSubscriber.receive(message("""
            {"trace_id":"trace-id","payload":"do-not-log"}
            """), "test:events", String.class, payload -> {
                MDC.put(StructuredLogger.ERROR_CODE, "test-only-listener-error");
                throw new IllegalStateException("do-not-log");
            })).doesNotThrowAnyException();

        assertThat(MDC.getCopyOfContextMap()).isEqualTo(workerContext);

        assertThat(logAppender.list).hasSize(1);

        assertThat(logAppender.list.getFirst().getLevel()).isEqualTo(Level.WARN);

        JsonNode log = objectMapper.readTree(logAppender.list.getFirst().getFormattedMessage());

        assertThat(log.path("operation").asText()).isEqualTo("dispatch");

        assertThat(log.path("trace_id").asText()).isEqualTo("trace-id");

        assertThat(log.path("route").isNull()).isTrue();

        assertThat(log.path("exception_type").asText()).isEqualTo(IllegalStateException.class.getName());

        assertThat(logAppender.list.getFirst().getFormattedMessage()).doesNotContain("do-not-log");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "\"\"", "\"   \""})
    @DisplayName("추적값이 없거나 비어 있으면 새 UUID로 데이터를 전달하고 빈 MDC를 복원한다")
    void generatesTraceIdForMissingOrBlankTraceId(String traceIdJson) {
        List<String> observedTraceIds = new ArrayList<>();
        List<String> observedPayloads = new ArrayList<>();
        String traceField = traceIdJson == null ? "" : ",\"trace_id\":" + traceIdJson;

        redisMessageSubscriber.receive(message("""
            {"payload":"test-value"%s}
            """.formatted(traceField)), "test:events", String.class, payload -> {
                observedTraceIds.add(MDC.get("traceId"));
                observedPayloads.add(payload);
            });

        assertThat(observedPayloads).containsExactly("test-value");

        assertThat(observedTraceIds).hasSize(1);

        String traceId = observedTraceIds.getFirst();

        assertThat(traceId).isNotBlank();

        assertThat(UUID.fromString(traceId).toString()).isEqualTo(traceId);

        JsonNode log = objectMapper.readTree(logAppender.list.getFirst().getFormattedMessage());

        assertThat(log.path("trace_id").asText()).isEqualTo(traceId);

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not-json", "null", "{}", "{\"trace_id\":\"trace-id\",\"payload\":null}",
        "{\"trace_id\":[],\"payload\":\"test-value\"}", "{\"payload\":\"test-value\"} {}"
    })
    @DisplayName("잘못된 전송 메시지는 처리하지 않고 이전 작업의 추적값을 로그에 섞지 않는다")
    void discardsInvalidEnvelope(String json) {
        Map<String, String> workerContext = Map.of("traceId", "previous-worker", "route", "/previous");
        MDC.setContextMap(workerContext);
        List<String> observedPayloads = new ArrayList<>();

        assertThatCode(() -> redisMessageSubscriber.receive(message(json), "test:events", String.class,
            observedPayloads::add)).doesNotThrowAnyException();

        assertThat(observedPayloads).isEmpty();

        assertThat(logAppender.list).hasSize(1);

        JsonNode log = objectMapper.readTree(logAppender.list.getFirst().getFormattedMessage());

        assertThat(log.path("trace_id").isNull()).isTrue();

        assertThat(log.path("route").isNull()).isTrue();

        assertThat(MDC.getCopyOfContextMap()).isEqualTo(workerContext);
    }

    private DefaultMessage message(String json) {
        return new DefaultMessage("test:events".getBytes(StandardCharsets.UTF_8),
            json.getBytes(StandardCharsets.UTF_8));
    }
}
