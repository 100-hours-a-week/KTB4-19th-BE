package com.homes.zipsai.conversation.ai;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

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
}
