package com.homes.zipsai.conversation.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.homes.zipsai.global.exception.AiUnavailableException;
import com.homes.zipsai.global.exception.InternalServerException;

class HttpAiComplaintFeedbackClientTest {

    private static final String BASE_URL = "http://ai.test";
    private static final String FEEDBACK_PATH = "/api/v2/ai/indexing/comment";
    private static final String DELETE_PATH = "/api/v2/ai/indexing/comment/delete";
    private static final String TRACE_ID = "request-trace-id";

    private MockRestServiceServer mockRestServiceServer;
    private HttpAiComplaintFeedbackClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockRestServiceServer = MockRestServiceServer.bindTo(builder).build();
        client = new HttpAiComplaintFeedbackClient(builder, BASE_URL, FEEDBACK_PATH, DELETE_PATH, "");
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

    @Test
    @DisplayName("댓글 삭제 요청을 명세 형식으로 보내고 202 빈 응답을 접수 완료로 처리한다")
    void sendsDeleteRequest() {
        mockRestServiceServer.expect(requestTo(BASE_URL + DELETE_PATH))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.trace_id").value(TRACE_ID))
            .andExpect(jsonPath("$.items[0].building_id").value(101))
            .andExpect(jsonPath("$.items[0].complaint_detail_id").value(9001))
            .andExpect(jsonPath("$.items[0].updated_at").value("2026-10-16T09:00:00+09:00"))
            .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThatCode(() -> client.delete(new AiComplaintFeedbackDeleteRequest(TRACE_ID,
            List.of(new AiComplaintFeedbackDeleteRequest.Item(101L, 9001L,
                OffsetDateTime.parse("2026-10-16T09:00:00+09:00")))))).doesNotThrowAnyException();

        mockRestServiceServer.verify();
    }

    private static AiComplaintFeedbackBatchRequest request() {
        return new AiComplaintFeedbackBatchRequest(TRACE_ID, List.of(new AiComplaintFeedbackRequest(
            9001L, 101L, "COMPLAINT", "leak", "지하주차장 B2", "천장에서 물이 떨어집니다",
            OffsetDateTime.parse("2026-10-14T00:00:12+09:00"), "배관 누수를 확인했습니다",
            OffsetDateTime.parse("2026-10-15T00:00:12+09:00"))));
    }

    @Test
    @DisplayName("AI가 건별 처리 결과를 응답하면 결과를 읽어 돌려준다")
    void readsPerItemResults() {
        mockRestServiceServer.expect(requestTo(BASE_URL + FEEDBACK_PATH))
            .andRespond(withSuccess("""
                {"trace_id":"request-trace-id","results":[
                  {"complaint_detail_id":9001,"updated_at":"2026-10-15T00:00:12+09:00",
                   "status":"failed","reason_code":"EMBEDDING_FAILED"}
                ]}
                """, MediaType.APPLICATION_JSON));

        AiComplaintFeedbackResponse response = client.send(request());

        assertThat(response.traceId()).isEqualTo(TRACE_ID);
        assertThat(response.find(9001L)).hasValueSatisfying(result -> {
            assertThat(result.status()).isEqualTo("failed");
            assertThat(result.reasonCode()).isEqualTo("EMBEDDING_FAILED");
            assertThat(result.updatedAt()).isEqualTo(OffsetDateTime.parse("2026-10-15T00:00:12+09:00"));
        });
    }

    @Test
    @DisplayName("본문 없이 202만 오면 빈 결과를 돌려준다")
    void returnsEmptyResultWhenAcceptedWithoutBody() {
        mockRestServiceServer.expect(requestTo(BASE_URL + FEEDBACK_PATH))
            .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThat(client.send(request()).results()).isEmpty();
    }
}
