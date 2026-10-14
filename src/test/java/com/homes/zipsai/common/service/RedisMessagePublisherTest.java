package com.homes.zipsai.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.homes.zipsai.common.event.RedisMessage;
import com.homes.zipsai.global.logging.StructuredLogger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Redis 공통 메시지 발행")
class RedisMessagePublisherTest {

    private StringRedisTemplate stringRedisTemplate;
    private ObjectMapper objectMapper;
    private RedisMessagePublisher redisMessagePublisher;
    private Logger logger;
    private ListAppender<ILoggingEvent> logAppender;
    private Map<String, String> previousMdc;

    @BeforeEach
    void setUp() {
        previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        MDC.put("traceId", "trace-id");
        stringRedisTemplate = mock(StringRedisTemplate.class);
        objectMapper = new ObjectMapper();
        redisMessagePublisher = new RedisMessagePublisher(stringRedisTemplate, objectMapper,
            new StructuredLogger(objectMapper));
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
    @DisplayName("요청 추적값으로 임의 데이터를 감싸 발행하고 같은 추적값으로 완료 로그를 남긴다")
    void publishesPayloadWithRequestTraceId() {
        redisMessagePublisher.publish("test:events", Map.of("value", "do-not-log"));

        assertThat(publishedMessage()).isEqualTo(objectMapper.readTree("""
            {"trace_id":"trace-id","payload":{"value":"do-not-log"}}
            """));

        assertThat(logAppender.list).hasSize(1);

        assertThat(logAppender.list.getFirst().getLevel()).isEqualTo(Level.INFO);

        JsonNode log = objectMapper.readTree(logAppender.list.getFirst().getFormattedMessage());

        assertThat(log.path("operation").asText()).isEqualTo("publish");

        assertThat(log.path("outcome").asText()).isEqualTo("ok");

        assertThat(log.path("channel").asText()).isEqualTo("test:events");

        assertThat(log.path("trace_id").asText()).isEqualTo("trace-id");

        assertThat(logAppender.list.getFirst().getFormattedMessage()).doesNotContain("do-not-log");

        assertThat(MDC.get("traceId")).isEqualTo("trace-id");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("요청 추적값이 없거나 비어 있으면 UUID를 생성하고 발행 로그에 같은 값을 사용한다")
    void generatesTraceIdWithoutRequestContext(String requestTraceId) {
        MDC.clear();
        if (requestTraceId != null) {
            MDC.put("traceId", requestTraceId);
        }
        Map<String, String> callerContext = MDC.getCopyOfContextMap();

        redisMessagePublisher.publish("test:events", "test-value");
        String traceId = publishedMessage().path("trace_id").asText();

        assertThat(traceId).isNotBlank();

        assertThat(UUID.fromString(traceId).toString()).isEqualTo(traceId);

        assertThat(logAppender.list).hasSize(1);

        JsonNode log = objectMapper.readTree(logAppender.list.getFirst().getFormattedMessage());

        assertThat(log.path("trace_id").asText()).isEqualTo(traceId);

        assertThat(MDC.getCopyOfContextMap()).isEqualTo(callerContext);
    }

    @Test
    @DisplayName("Redis 발행 실패를 같은 추적값으로 기록하고 호출 작업을 실패시키지 않는다")
    void isolatesRedisFailureFromCaller() {
        RedisConnectionFailureException exception = new RedisConnectionFailureException("do-not-log");
        when(stringRedisTemplate.convertAndSend(eq("test:events"), anyString())).thenThrow(exception);

        assertThatCode(() -> redisMessagePublisher.publish("test:events", "test-value"))
            .doesNotThrowAnyException();

        assertThat(publishedMessage().path("trace_id").asText()).isEqualTo("trace-id");

        assertThat(logAppender.list).hasSize(1);

        assertThat(logAppender.list.getFirst().getLevel()).isEqualTo(Level.WARN);

        JsonNode log = objectMapper.readTree(logAppender.list.getFirst().getFormattedMessage());

        assertThat(log.path("trace_id").asText()).isEqualTo("trace-id");

        assertThat(log.path("outcome").asText()).isEqualTo("fail");

        assertThat(logAppender.list.getFirst().getFormattedMessage()).doesNotContain("do-not-log");
    }

    @Test
    @DisplayName("JSON 변환 실패는 추적값과 함께 기록하고 잘못된 메시지를 Redis에 보내지 않는다")
    void isolatesSerializationFailureFromCaller() {
        ObjectMapper failingObjectMapper = mock(ObjectMapper.class);
        JacksonException exception = mock(JacksonException.class);
        when(failingObjectMapper.writeValueAsString(any(RedisMessage.class))).thenThrow(exception);
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        RedisMessagePublisher failingPublisher = new RedisMessagePublisher(stringRedisTemplate,
            failingObjectMapper, structuredLogger);

        assertThatCode(() -> failingPublisher.publish("test:events", "test-value")).doesNotThrowAnyException();

        verify(structuredLogger).pubSubFailed("publish", "test:events", "trace-id", exception);

        verifyNoInteractions(stringRedisTemplate);
    }

    private JsonNode publishedMessage() {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);

        verify(stringRedisTemplate).convertAndSend(eq("test:events"), message.capture());

        return objectMapper.readTree(message.getValue());
    }
}
