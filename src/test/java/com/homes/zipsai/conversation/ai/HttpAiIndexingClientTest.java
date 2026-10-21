package com.homes.zipsai.conversation.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.homes.zipsai.global.exception.AiUnavailableException;

class HttpAiIndexingClientTest {

    private static final String BASE_URL = "http://ai.test";
    private static final String INDEXING_PATH = "/api/v3/ai/indexing/jobs";
    private static final String TRACE_ID = "request-trace-id";

    private MockRestServiceServer mockRestServiceServer;
    private HttpAiIndexingClient httpAiIndexingClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockRestServiceServer = MockRestServiceServer.bindTo(builder).build();
        httpAiIndexingClient = new HttpAiIndexingClient(builder, BASE_URL, INDEXING_PATH, "");
    }

    @Test
    @DisplayName("문서 색인 요청 본문에 trace_id를 building_id와 같은 레벨로 담는다")
    void sendsTraceIdWithIndexingRequest() {
        mockRestServiceServer.expect(requestTo(BASE_URL + INDEXING_PATH))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.building_id").value(10))
            .andExpect(jsonPath("$.trace_id").value(TRACE_ID))
            .andExpect(jsonPath("$.doc_id").value("100"))
            .andRespond(withStatus(HttpStatus.ACCEPTED));

        httpAiIndexingClient.index(new AiIndexingRequest(10L, TRACE_ID, "100", "관리규약",
            "s3://test-bucket/documents/rule.pdf", List.of("100")));

        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("문서 정리 요청 본문에 trace_id를 담는다")
    void sendsTraceIdWithCleanupRequest() {
        mockRestServiceServer.expect(requestTo(BASE_URL + INDEXING_PATH))
            .andExpect(jsonPath("$.trace_id").value(TRACE_ID))
            .andExpect(jsonPath("$.valid_doc_ids[0]").value("100"))
            .andRespond(withStatus(HttpStatus.ACCEPTED));

        httpAiIndexingClient.cleanup(10L, TRACE_ID, List.of("100"));

        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("AI가 건별 처리 결과를 응답하면 결과를 읽어 돌려준다")
    void readsIndexingResults() {
        mockRestServiceServer.expect(requestTo(BASE_URL + INDEXING_PATH))
            .andRespond(withSuccess("""
                {"trace_id":"request-trace-id","results":[
                  {"kind":"document_index","doc_id":"100","status":"failed","reason_code":"PARSE_FAILED"},
                  {"kind":"document_cleanup","status":"skipped","reason_code":"INDEX_FAILED"}
                ]}
                """, MediaType.APPLICATION_JSON));

        AiIndexingResponse response = httpAiIndexingClient.index(new AiIndexingRequest(10L, TRACE_ID, "100",
            "관리규약", "s3://test-bucket/documents/rule.pdf", List.of("100")));

        assertThat(response.indexResult("100")).hasValueSatisfying(result -> {
            assertThat(result.status()).isEqualTo("failed");
            assertThat(result.reasonCode()).isEqualTo("PARSE_FAILED");
        });
        assertThat(response.cleanupResult()).hasValueSatisfying(result ->
            assertThat(result.reasonCode()).isEqualTo("INDEX_FAILED"));
    }

    @Test
    @DisplayName("결과 본문 없이 접수만 하면 빈 결과를 돌려준다")
    void returnsEmptyResultWhenAcceptedWithoutBody() {
        mockRestServiceServer.expect(requestTo(BASE_URL + INDEXING_PATH))
            .andRespond(withStatus(HttpStatus.ACCEPTED));

        AiIndexingResponse response = httpAiIndexingClient.cleanup(10L, TRACE_ID, List.of());

        assertThat(response.results()).isEmpty();
    }

    @Test
    @DisplayName("AI가 일시적으로 응답하지 못하면 재시도 가능한 예외로 알린다")
    void throwsRetryableExceptionWhenUnavailable() {
        mockRestServiceServer.expect(requestTo(BASE_URL + INDEXING_PATH))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> httpAiIndexingClient.cleanup(10L, TRACE_ID, List.of()))
            .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    @DisplayName("결과의 retryable 값을 읽는다")
    void readsRetryable() {
        mockRestServiceServer.expect(requestTo(BASE_URL + INDEXING_PATH))
            .andRespond(withSuccess("""
                {"trace_id":"t","results":[
                  {"kind":"document_index","doc_id":"100","status":"failed",
                   "reason_code":"EMBEDDING_FAILED","retryable":true}
                ]}
                """, MediaType.APPLICATION_JSON));

        AiIndexingResponse response = httpAiIndexingClient.index(new AiIndexingRequest(10L, TRACE_ID, "100",
            "관리규약", "s3://test-bucket/documents/rule.pdf", List.of("100")));

        assertThat(response.indexResult("100")).hasValueSatisfying(result ->
            assertThat(result.isRetryable()).isTrue());
    }

    @Test
    @DisplayName("429와 Retry-After를 받으면 기다릴 시간을 담은 재시도 예외로 알린다")
    void throwsRetryLaterWithRetryAfter() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "30");
        mockRestServiceServer.expect(requestTo(BASE_URL + INDEXING_PATH))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));

        assertThatThrownBy(() -> httpAiIndexingClient.cleanup(10L, TRACE_ID, List.of()))
            .isInstanceOfSatisfying(AiRetryLaterException.class,
                exception -> assertThat(exception.retryAfter()).isEqualTo(Duration.ofSeconds(30)));
    }
}
