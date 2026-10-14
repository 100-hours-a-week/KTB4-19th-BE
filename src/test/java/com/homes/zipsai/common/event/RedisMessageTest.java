package com.homes.zipsai.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.homes.zipsai.global.exception.InvalidPubSubFieldException;
import com.homes.zipsai.global.exception.InvalidPubSubFieldException.Reason;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Redis 공통 메시지 계약")
class RedisMessageTest {

    @Test
    @DisplayName("알림과 무관한 데이터도 trace_id와 payload를 분리해 JSON으로 주고받는다")
    void roundTripsTraceIdAndPayload() {
        ObjectMapper objectMapper = new ObjectMapper();
        RedisMessage<Map<String, String>> message = new RedisMessage<>("trace-id", Map.of("value", "test-value"));
        String json = """
            {"trace_id":"trace-id","payload":{"value":"test-value"}}
            """;

        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(message)))
            .isEqualTo(objectMapper.readTree(json));

        assertThat(objectMapper.readValue(json, new TypeReference<RedisMessage<Map<String, String>>>() { }))
            .isEqualTo(message);
    }

    @Test
    @DisplayName("전달할 데이터가 없는 Redis 메시지는 만들지 않는다")
    void rejectsMissingPayload() {
        assertThatThrownBy(() -> new RedisMessage<>("trace-id", null))
            .isInstanceOf(InvalidPubSubFieldException.class)
            .extracting("field", "reason")
            .containsExactly("payload", Reason.REQUIRED_VALUE);
    }
}
