package com.homes.zipsai.conversation.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.homes.zipsai.conversation.ai.error.AiConverseErrorType;
import com.homes.zipsai.conversation.ai.error.AiConverseException;

class AiConverseResponseValidationTest {

    private static final String TURN_ID = "turn-1";

    @ParameterizedTest
    @MethodSource("invalidResponses")
    @DisplayName("성공 코드와 필수 응답값이 없거나 잘못되면 재시도할 응답 오류로 구분한다")
    void rejectsInvalidResponse(AiConverseResponse response) {
        assertThatThrownBy(() -> response.validateFor(TURN_ID))
            .isInstanceOfSatisfying(AiConverseException.class, exception -> {
                assertThat(exception.getErrorType()).isEqualTo(AiConverseErrorType.INVALID_RESPONSE);

                assertThat(exception.code).isEqualTo("INTERNAL_SERVER_ERROR");
            });
    }

    @Test
    @DisplayName("다른 turnId의 응답은 실패 횟수를 늘려 재시도할 짝 불일치 오류로 구분한다")
    void rejectsUnpairedResponse() {
        AiConverseResponse response = new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, "other-turn",
            data(AiRoute.CLARIFY, "어떤 도움이 필요한가요?"));

        assertThatThrownBy(() -> response.validateFor(TURN_ID))
            .isInstanceOfSatisfying(AiConverseException.class, exception -> {
                assertThat(exception.getErrorType()).isEqualTo(AiConverseErrorType.TURN_ID_MISMATCH);

                assertThat(exception.getErrorType().getRetryPolicy())
                    .isEqualTo(AiConverseErrorType.RetryPolicy.LIMITED);
            });
    }

    @ParameterizedTest
    @MethodSource("validResponses")
    @DisplayName("요청과 짝이 맞고 필수값이 있으면 선택 응답 필드가 없어도 허용한다")
    void acceptsResponseWithoutOptionalFields(AiConverseResponse response) {
        assertThatCode(() -> response.validateFor(TURN_ID)).doesNotThrowAnyException();
    }

    private static Stream<AiConverseResponse> invalidResponses() {
        AiConverseResponse.Data data = data(AiRoute.CLARIFY, "어떤 도움이 필요한가요?");
        return Stream.of(
            new AiConverseResponse(null, TURN_ID, data),
            new AiConverseResponse("ai_response_failed", TURN_ID, data),
            new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, TURN_ID, null),
            new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, null, data),
            new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, " ", data),
            response(data(null, "어떤 도움이 필요한가요?")),
            response(data(AiRoute.CLARIFY, null)),
            response(data(AiRoute.CLARIFY, " ")));
    }

    private static Stream<AiConverseResponse> validResponses() {
        return Stream.of(AiRoute.COMPLAINT, AiRoute.KNOWLEDGE, AiRoute.CLARIFY)
            .map(route -> response(data(route, "어떤 도움이 필요한가요?")));
    }

    private static AiConverseResponse response(AiConverseResponse.Data data) {
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, TURN_ID, data);
    }

    private static AiConverseResponse.Data data(AiRoute route, String reply) {
        return new AiConverseResponse.Data(route, null, reply, null);
    }
}
