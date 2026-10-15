package com.homes.zipsai.conversation.ai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.homes.zipsai.global.exception.AiUnavailableException;
import com.homes.zipsai.global.exception.InternalServerException;

class HttpAiComplaintFeedbackClientTest {

    private static final String BASE_URL = "http://ai.test";
    private static final String FEEDBACK_PATH = "/api/v2/ai/indexing/comment";
    private static final String TRACE_ID = "request-trace-id";

    private MockRestServiceServer mockRestServiceServer;
    private HttpAiComplaintFeedbackClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockRestServiceServer = MockRestServiceServer.bindTo(builder).build();
        client = new HttpAiComplaintFeedbackClient(builder, BASE_URL, FEEDBACK_PATH, "");
    }

    @Test
    @DisplayName("댓글 접수 요청을 명세 형식으로 보내고 202 빈 응답을 접수 완료로 처리한다")
    void sendsCommentsAndAcceptsEmptyResponse() {
        mockRestServiceServer.expect(requestTo(BASE_URL + FEEDBACK_PATH))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.trace_id").value(TRACE_ID))
            .andExpect(jsonPath("$.items[0].complaint_detail_id").value(9001))
            .andExpect(jsonPath("$.items[0].building_id").value(101))
            .andExpect(jsonPath("$.items[0].complaint_type").value("COMPLAINT"))
            .andExpect(jsonPath("$.items[0].comment").value("배관 누수를 확인했습니다"))
            .andExpect(jsonPath("$.items[0].updated_at").value("2026-10-15T00:00:12+09:00"))
            .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThatCode(() -> client.send(request())).doesNotThrowAnyException();

        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("AI 의존 서버가 준비되지 않은 503은 재시도 대상 예외로 바꾼다")
    void throwsRetryableExceptionWhenDependencyNotReady() {
        mockRestServiceServer.expect(requestTo(BASE_URL + FEEDBACK_PATH))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.send(request())).isInstanceOf(AiUnavailableException.class);
    }

    @Test
    @DisplayName("검증 실패 422는 재시도하지 않는 예외로 바꾼다")
    void throwsNonRetryableExceptionWhenValidationFails() {
        mockRestServiceServer.expect(requestTo(BASE_URL + FEEDBACK_PATH))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT));

        assertThatThrownBy(() -> client.send(request())).isInstanceOf(InternalServerException.class);
    }

    private static AiComplaintFeedbackBatchRequest request() {
        return new AiComplaintFeedbackBatchRequest(TRACE_ID, List.of(new AiComplaintFeedbackRequest(
            9001L, 101L, "COMPLAINT", "leak", "지하주차장 B2", "천장에서 물이 떨어집니다",
            OffsetDateTime.parse("2026-10-14T00:00:12+09:00"), "배관 누수를 확인했습니다",
            OffsetDateTime.parse("2026-10-15T00:00:12+09:00"))));
    }
}
