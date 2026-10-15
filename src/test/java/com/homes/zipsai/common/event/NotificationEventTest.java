package com.homes.zipsai.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.homes.zipsai.common.domain.ComplaintNotificationContent.Type;
import com.homes.zipsai.global.exception.InvalidPubSubFieldException;
import com.homes.zipsai.global.exception.InvalidPubSubFieldException.Reason;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Pub/Sub 알림 메시지 계약")
class NotificationEventTest {

    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        json = new ObjectMapper();
    }

    @ParameterizedTest
    @EnumSource(Type.class)
    @DisplayName("두 민원 알림 타입을 대상 사용자와 사용자별 알림 ID가 포함된 JSON으로 주고받는다")
    void roundTripsNotificationEvent(Type eventType) {
        NotificationEvent event = new NotificationEvent(eventType, 42L, 123L);
        String payload = """
            {"eventType":"%s","userId":42,"resourceId":123}
            """.formatted(eventType.name());

        assertThat(json.readTree(json.writeValueAsString(event))).isEqualTo(json.readTree(payload));

        assertThat(json.readValue(payload, NotificationEvent.class)).isEqualTo(event);
    }

    @Test
    @DisplayName("이벤트 타입이 없는 메시지는 만들지 않는다")
    void rejectsMissingEventType() {
        assertThatThrownBy(() -> new NotificationEvent(null, 42L, 123L))
            .isInstanceOf(InvalidPubSubFieldException.class)
            .extracting("field", "reason")
            .containsExactly("eventType", Reason.REQUIRED_VALUE);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @DisplayName("대상 사용자 ID가 양수가 아니면 메시지를 만들지 않는다")
    void rejectsNonPositiveUserId(long userId) {
        assertThatThrownBy(() -> new NotificationEvent(Type.COMPLAINT_CREATED, userId, 123L))
            .isInstanceOf(InvalidPubSubFieldException.class)
            .extracting("field", "reason")
            .containsExactly("userId", Reason.INVALID_ID);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @DisplayName("사용자별 알림 ID가 양수가 아니면 메시지를 만들지 않는다")
    void rejectsNonPositiveResourceId(long resourceId) {
        assertThatThrownBy(() -> new NotificationEvent(Type.COMPLAINT_CREATED, 42L, resourceId))
            .isInstanceOf(InvalidPubSubFieldException.class)
            .extracting("field", "reason")
            .containsExactly("resourceId", Reason.INVALID_ID);
    }

    @Test
    @DisplayName("JSON 역직렬화가 검증 예외를 감싸도 실패 필드와 원인은 유지된다")
    void preservesValidationDetailsWhenDeserializing() {
        assertThatThrownBy(() -> json.readValue("""
            {"eventType":"COMPLAINT_CREATED","userId":0,"resourceId":123}
            """, NotificationEvent.class))
            .isInstanceOf(JacksonException.class)
            .rootCause()
            .isInstanceOf(InvalidPubSubFieldException.class)
            .extracting("field", "reason")
            .containsExactly("userId", Reason.INVALID_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not-json",
        "{}",
        "{\"userId\":42,\"resourceId\":123}",
        "{\"eventType\":\"COMPLAINT_CREATED\",\"resourceId\":123}",
        "{\"eventType\":\"COMPLAINT_CREATED\",\"userId\":42}",
        "{\"eventType\":null,\"userId\":42,\"resourceId\":123}",
        "{\"eventType\":\"UNKNOWN\",\"userId\":42,\"resourceId\":123}",
        "{\"eventType\":\"COMPLAINT_CREATED\",\"userId\":null,\"resourceId\":123}",
        "{\"eventType\":\"COMPLAINT_CREATED\",\"userId\":42,\"resourceId\":null}",
        "{\"eventType\":\"COMPLAINT_CREATED\",\"userId\":0,\"resourceId\":123}",
        "{\"eventType\":\"COMPLAINT_CREATED\",\"userId\":42,\"resourceId\":-1}"
    })
    @DisplayName("잘못된 JSON과 필수 필드 누락 및 유효하지 않은 타입과 ID를 거부한다")
    void rejectsInvalidPayload(String payload) {
        assertThatThrownBy(() -> json.readValue(payload, NotificationEvent.class))
            .isInstanceOf(JacksonException.class);
    }
}
