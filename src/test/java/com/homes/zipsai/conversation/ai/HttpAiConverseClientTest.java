package com.homes.zipsai.conversation.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.homes.zipsai.global.exception.AiUnavailableException;
import com.homes.zipsai.global.exception.TooManyRequestsException;

class HttpAiConverseClientTest {

    private static final String BASE_URL = "http://ai.test";
    private static final String CONVERSE_URL = BASE_URL + "/api/v3/ai/converse";
    private static final String TRACE_ID = "6f6d8b2e-0b0b-4a1e-9f2a-3f9d5c1a7e11";

    private MockRestServiceServer server;
    private HttpAiConverseClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpAiConverseClient(builder, BASE_URL);
    }

    @Test
    void sendsContractFieldsAndReadsComplaintReply() {
        server.expect(requestTo(CONVERSE_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.building_id").value(1))
            .andExpect(jsonPath("$.room_no").value("302"))
            .andExpect(jsonPath("$.resident_id").value("7"))
            .andExpect(jsonPath("$.conversation_id").value("11"))
            .andExpect(jsonPath("$.trace_id").value(TRACE_ID))
            .andExpect(jsonPath("$.current_route").value("complaint"))
            .andExpect(jsonPath("$.current_complaint_state").value("collecting"))
            .andExpect(jsonPath("$.message.message_id").value("21"))
            .andExpect(jsonPath("$.message.text").value("안방 천장 가운데요"))
            .andExpect(jsonPath("$.message.image_urls").isEmpty())
            .andExpect(jsonPath("$.conversation_history[0].role").value("user"))
            .andExpect(jsonPath("$.conversation_history[0].message_id").value("19"))
            .andExpect(jsonPath("$.conversation_history[0].text").value("천장에서 물이 새요"))
            .andExpect(jsonPath("$.complaint_draft.symptom").value("천장에서 물이 새요"))
            .andRespond(withSuccess("""
                {
                  "code": "ai_response_success",
                  "trace_id": "%s",
                  "data": {
                    "route": "complaint",
                    "complaint_intent": "register",
                    "next_complaint_state": null,
                    "reply": "아래 내용으로 민원을 접수할까요?",
                    "result": {
                      "draft_patch": {
                        "issue_type": "water_supply",
                        "location": "안방 천장",
                        "symptom": "천장에서 물이 새요"
                      },
                      "qa_card_draft": null,
                      "missing_fields": [],
                      "citations": [],
                      "has_sufficient_evidence": null,
                      "image_analysis": null
                    },
                    "meta": { "model": "gpt-5-nano", "timing_ms": 842 }
                  }
                }
                """.formatted(TRACE_ID), MediaType.APPLICATION_JSON));

        AiConverseResponse response = client.converse(request());

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.traceId()).isEqualTo(TRACE_ID);
        assertThat(response.route()).isEqualTo(AiRoute.COMPLAINT);
        assertThat(response.nextComplaintState()).isNull();
        assertThat(response.reply()).isEqualTo("아래 내용으로 민원을 접수할까요?");
        assertThat(response.draftPatch().location()).isEqualTo("안방 천장");
        assertThat(response.qaCardQuestion()).isNull();
        assertThat(response.isConversationComplete()).isTrue();
        server.verify();
    }

    @Test
    void readsQaCardDraftWhenEvidenceIsMissing() {
        server.expect(requestTo(CONVERSE_URL))
            .andRespond(withSuccess("""
                {
                  "code": "ai_response_success",
                  "trace_id": "%s",
                  "data": {
                    "route": "knowledge",
                    "next_complaint_state": null,
                    "reply": "근거를 찾지 못했습니다.",
                    "result": {
                      "draft_patch": null,
                      "qa_card_draft": { "question": "엘리베이터 정기 점검 일정 문의" },
                      "missing_fields": [],
                      "has_sufficient_evidence": false
                    }
                  }
                }
                """.formatted(TRACE_ID), MediaType.APPLICATION_JSON));

        AiConverseResponse response = client.converse(request());

        assertThat(response.route()).isEqualTo(AiRoute.KNOWLEDGE);
        assertThat(response.qaCardQuestion()).isEqualTo("엘리베이터 정기 점검 일정 문의");
        assertThat(response.isConversationComplete()).isTrue();
        server.verify();
    }

    @Test
    void mapsRateLimitAndDependencyFailuresToOwnErrors() {
        server.expect(requestTo(CONVERSE_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> client.converse(request())).isInstanceOf(TooManyRequestsException.class);

        server.reset();
        server.expect(requestTo(CONVERSE_URL)).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));
        assertThatThrownBy(() -> client.converse(request())).isInstanceOf(AiUnavailableException.class);
    }

    private AiConverseRequest request() {
        return new AiConverseRequest(
            1L, "302", "7", "11", TRACE_ID,
            AiRoute.COMPLAINT, AiComplaintState.COLLECTING,
            new AiConverseRequest.MessagePayload("21", "안방 천장 가운데요", List.of()),
            List.of(new AiConverseRequest.HistoryMessage("19", AiTurnRole.USER, "천장에서 물이 새요", List.of())),
            new AiConverseRequest.ComplaintDraftPayload(null, "천장에서 물이 새요", null, List.of()));
    }
}
