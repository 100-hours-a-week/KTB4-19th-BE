package com.homes.zipsai.conversation.ai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.homes.zipsai.global.exception.AiUnavailableException;
import com.homes.zipsai.global.exception.InternalServerException;

class HttpAiIndexingClientTest {

    private static final String BASE_URL = "http://ai.test";
    private static final String PATH = "/api/v3/ai/indexing/jobs";

    private MockRestServiceServer server;
    private HttpAiIndexingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpAiIndexingClient(builder, BASE_URL, PATH, "");
    }

    @Test
    void accepts202WithEmptyBody() {
        server.expect(requestTo(BASE_URL + PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.building_id").value(101))
                .andExpect(jsonPath("$.doc_id").value("notice-002"))
                .andExpect(jsonPath("$.title").value("승강기 정기 점검 안내"))
                .andExpect(jsonPath("$.file_key").value("s3://zipsai-files/buildings/101/notice-002.pdf"))
                .andExpect(jsonPath("$.valid_doc_ids[2]").value("notice-002"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        assertThatCode(() -> client.index(request())).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void mapsServerFailureToRetryableAiUnavailable() {
        server.expect(requestTo(BASE_URL + PATH)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.index(request())).isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void mapsContractFailureToInternalServerError() {
        server.expect(requestTo(BASE_URL + PATH)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.index(request())).isInstanceOf(InternalServerException.class);
    }

    private AiIndexingRequest request() {
        return new AiIndexingRequest(101L, "notice-002", "승강기 정기 점검 안내",
                "s3://zipsai-files/buildings/101/notice-002.pdf",
                List.of("rule-2026", "notice-001", "notice-002"));
    }
}
