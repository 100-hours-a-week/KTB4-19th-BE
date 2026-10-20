package com.homes.zipsai.conversation.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import com.homes.zipsai.global.exception.AiUnavailableException;
import com.homes.zipsai.global.exception.TooManyRequestsException;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

class HttpAiConverseClientTest {

    private static final String BASE_URL = "http://ai.test";
    private static final String CONVERSE_STREAM_PATH = "/api/v3/ai/converse/stream";
    private static final String CONVERSE_STREAM_URL = BASE_URL + CONVERSE_STREAM_PATH;
    private static final String TURN_ID = "6f6d8b2e-0b0b-4a1e-9f2a-3f9d5c1a7e11";
    private static final String TRACE_ID = "request-trace-id";
    private static final JsonMapper JSON_MAPPER = new JsonMapper();

    private MockRestServiceServer mockRestServiceServer;
    private HttpAiConverseClient httpAiConverseClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockRestServiceServer = MockRestServiceServer.bindTo(builder).build();
        httpAiConverseClient = new HttpAiConverseClient(builder.baseUrl(BASE_URL).build(), CONVERSE_STREAM_PATH,
            JSON_MAPPER);
    }

    @Test
    @DisplayName("계약한 필드 이름과 값으로 AI 서버에 스트림을 요청한다")
    void sendsContractFields() {
        mockRestServiceServer.expect(requestTo(CONVERSE_STREAM_URL))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE))
            .andExpect(jsonPath("$.building_id").value(1))
            .andExpect(jsonPath("$.room_no").value("302"))
            .andExpect(jsonPath("$.resident_id").value("7"))
            .andExpect(jsonPath("$.conversation_id").value("11"))
            .andExpect(jsonPath("$.turn_id").value(TURN_ID))
            .andExpect(jsonPath("$.trace_id").value(TRACE_ID))
            .andExpect(jsonPath("$.current_route").value("complaint"))
            .andExpect(jsonPath("$.current_complaint_state").value("collecting"))
            .andExpect(jsonPath("$.message.message_id").value("21"))
            .andExpect(jsonPath("$.message.text").value("안방 천장 가운데요"))
            .andExpect(jsonPath("$.message.images[0].attachmentId").value(32))
            .andExpect(jsonPath("$.message.images[0].url").value("https://s3.test/ceiling.jpg"))
            .andExpect(jsonPath("$.conversation_history[0].role").value("user"))
            .andExpect(jsonPath("$.conversation_history[0].message_id").value("19"))
            .andExpect(jsonPath("$.conversation_history[0].text").value("천장에서 물이 새요"))
            .andExpect(jsonPath("$.conversation_history[0].images[0].attachmentId").value(31))
            .andExpect(jsonPath("$.conversation_history[0].images[0].summary").value("천장 얼룩"))
            .andExpect(jsonPath("$.conversation_history[0].images[0].ocrText").value("관리실 010"))
            .andExpect(jsonPath("$.conversation_history[1].role").value("assistant"))
            .andExpect(jsonPath("$.conversation_history[1].images").doesNotExist())
            .andExpect(jsonPath("$.complaint_draft.issue_type").value("leak"))
            .andExpect(jsonPath("$.complaint_draft.symptom").value("천장에서 물이 새요"))
            .andExpect(jsonPath("$.complaint_draft.attachmentIds[0]").value(31))
            .andRespond(withStream(status(), complete(complaintReply()), done()));

        httpAiConverseClient.converse(request(), delta -> { });

        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("답변 조각을 받은 순서대로 넘긴다")
    void passesReplyDeltasInOrder() {
        mockRestServiceServer.expect(requestTo(CONVERSE_STREAM_URL))
            .andRespond(withStream(status(), delta("아래 내용으로 "), delta("민원을 "), delta("접수할까요?"),
                complete(complaintReply()), done()));
        List<String> deltas = new ArrayList<>();

        httpAiConverseClient.converse(request(), deltas::add);

        assertThat(deltas).containsExactly("아래 내용으로 ", "민원을 ", "접수할까요?");
    }

    @Test
    @DisplayName("최종 응답 이벤트의 민원 응답을 읽는다")
    void readsComplaintReply() {
        mockRestServiceServer.expect(requestTo(CONVERSE_STREAM_URL))
            .andRespond(withStream(status(), delta("아래 내용으로 민원을 접수할까요?"), complete(complaintReply()),
                done()));

        AiConverseResponse response = httpAiConverseClient.converse(request(), delta -> { });

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.turnId()).isEqualTo(TURN_ID);
        assertThat(response.route()).isEqualTo(AiRoute.COMPLAINT);
        assertThat(response.nextComplaintState()).isNull();
        assertThat(response.reply()).isEqualTo("아래 내용으로 민원을 접수할까요?");
        assertThat(response.complaintDraft().location()).isEqualTo("안방 천장");
        assertThat(response.qaCardQuestion()).isNull();
        assertThat(response.isConversationComplete()).isTrue();
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("AI 서버가 답변 도중 오류 이벤트를 보내면 AI 사용 불가 예외로 바꾼다")
    void mapsErrorEventToAiUnavailable() {
        mockRestServiceServer.expect(requestTo(CONVERSE_STREAM_URL))
            .andRespond(withStream(status(), delta("관리비는 "),
                "{\"type\":\"error\",\"message\":\"응답 생성에 실패했습니다.\"}", done()));

        assertThatThrownBy(() -> httpAiConverseClient.converse(request(), delta -> { }))
            .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    @DisplayName("최종 응답 없이 스트림이 끝나면 AI 사용 불가 예외로 바꾼다")
    void mapsStreamWithoutCompleteToAiUnavailable() {
        mockRestServiceServer.expect(requestTo(CONVERSE_STREAM_URL))
            .andRespond(withStream(status(), delta("관리비는 "), done()));

        assertThatThrownBy(() -> httpAiConverseClient.converse(request(), delta -> { }))
            .isInstanceOf(AiUnavailableException.class);
    }

    @Test
    @DisplayName("답변 조각이 읽기 제한 시간 넘게 오지 않으면 AI 사용 불가 예외로 바꾼다")
    void mapsStalledStreamToAiUnavailable() throws Exception {
        CountDownLatch testFinished = new CountDownLatch(1);
        HttpServer stalledServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stalledServer.createContext(CONVERSE_STREAM_PATH, exchange -> {
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE);
            exchange.sendResponseHeaders(HttpStatus.OK.value(), 0);
            OutputStream responseBody = exchange.getResponseBody();
            responseBody.write(("data: " + status() + "\n\n").getBytes(StandardCharsets.UTF_8));
            responseBody.flush();
            awaitQuietly(testFinished);
            exchange.close();
        });
        stalledServer.start();
        HttpAiConverseClient client = new HttpAiConverseClient(RestClient.builder(),
            "http://127.0.0.1:" + stalledServer.getAddress().getPort(), CONVERSE_STREAM_PATH, Duration.ofSeconds(1),
            Duration.ofMillis(300), JSON_MAPPER);
        long startedNanos = System.nanoTime();

        try {
            assertThatThrownBy(() -> client.converse(request(), delta -> { }))
                .isInstanceOf(AiUnavailableException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - startedNanos)).isLessThan(Duration.ofSeconds(3));
        } finally {
            testFinished.countDown();
            stalledServer.stop(0);
        }
    }

    @Test
    @DisplayName("AI 서버의 사진 분석 결과와 민원 초안 사진 필드를 읽는다")
    void readsImageAnalysisAndDraftAttachments() {
        mockRestServiceServer.expect(requestTo(CONVERSE_STREAM_URL))
            .andRespond(withStream(complete("""
                {
                  "code": "ai_response_success",
                  "turn_id": "%s",
                  "data": {
                    "route": "complaint",
                    "next_complaint_state": "collecting",
                    "reply": "사진 확인했어요. 위치가 어디인가요?",
                    "result": {
                      "complaint_draft": {
                        "issue_type": "leak",
                        "symptom": "천장 누수",
                        "attachmentIds": [31, 32],
                        "representative_attachment_id": 32
                      },
                      "missing_fields": ["location"],
                      "citations": [],
                      "image_analysis": {
                        "images": [
                          { "attachmentId": 31, "summary": "천장 얼룩", "ocrText": "관리실 010" }
                        ]
                      }
                    }
                  }
                }
                """.formatted(TURN_ID)), done()));

        AiConverseResponse response = httpAiConverseClient.converse(request(), delta -> { });

        assertThat(response.complaintDraft().issueType()).isEqualTo("leak");
        assertThat(response.complaintDraft().attachmentIds()).containsExactly(31L, 32L);
        assertThat(response.complaintDraft().representativeAttachmentId()).isEqualTo(32L);
        assertThat(response.imageObservations())
            .containsExactly(new AiConverseResponse.ImageObservation(31L, "천장 얼룩", "관리실 010"));
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("AI 서버가 민원 초안에 매긴 긴급도를 읽는다")
    void readsDraftUrgency() {
        mockRestServiceServer.expect(requestTo(CONVERSE_STREAM_URL))
            .andRespond(withStream(complete("""
                {
                  "code": "ai_response_success",
                  "turn_id": "%s",
                  "data": {
                    "route": "complaint",
                    "next_complaint_state": null,
                    "reply": "A동 3층 욕실의 누수 문제로 민원을 접수할까요?",
                    "result": {
                      "complaint_draft": {
                        "issue_type": "leak",
                        "location": "A동 3층 욕실",
                        "symptom": "세탁기 밑으로 물이 샘",
                        "urgency_score": 80,
                        "is_urgent": true,
                        "urgency_version": "urgency-llm-v1-phase0"
                      },
                      "missing_fields": [],
                      "citations": []
                    }
                  }
                }
                """.formatted(TURN_ID)), done()));

        AiConverseResponse response = httpAiConverseClient.converse(request(), delta -> { });

        assertThat(response.complaintDraft().urgencyScore()).isEqualTo(80);
        assertThat(response.complaintDraft().isUrgent()).isTrue();
        assertThat(response.complaintDraft().urgencyVersion()).isEqualTo("urgency-llm-v1-phase0");
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("최종 응답의 사진 필드가 snake_case 이름으로 와도 읽는다")
    void readsSnakeCaseImageFields() {
        mockRestServiceServer.expect(requestTo(CONVERSE_STREAM_URL))
            .andRespond(withStream(complete("""
                {
                  "code": "ai_response_success",
                  "turn_id": "%s",
                  "data": {
                    "route": "complaint",
                    "next_complaint_state": "collecting",
                    "reply": "사진 확인했어요. 위치가 어디인가요?",
                    "result": {
                      "complaint_draft": {
                        "issue_type": "leak",
                        "symptom": "천장 누수",
                        "attachment_ids": [31, 32],
                        "representative_attachment_id": 32
                      },
                      "missing_fields": ["location"],
                      "citations": [],
                      "image_analysis": {
                        "images": [
                          { "attachment_id": 31, "summary": "천장 얼룩", "ocr_text": "관리실 010" }
                        ]
                      }
                    }
                  }
                }
                """.formatted(TURN_ID)), done()));

        AiConverseResponse response = httpAiConverseClient.converse(request(), delta -> { });

        assertThat(response.complaintDraft().attachmentIds()).containsExactly(31L, 32L);
        assertThat(response.imageObservations())
            .containsExactly(new AiConverseResponse.ImageObservation(31L, "천장 얼룩", "관리실 010"));
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("오프셋 없는 발생 시각이 담긴 응답도 읽는다")
    void readsResponseWithoutOffsetOnOccurredAt() {
        mockRestServiceServer.expect(requestTo(CONVERSE_STREAM_URL))
            .andRespond(withStream(complete("""
                {
                  "code": "ai_response_success",
                  "turn_id": "%s",
                  "data": {
                    "route": "complaint",
                    "next_complaint_state": "collecting",
                    "reply": "언제부터 그랬나요?",
                    "result": {
                      "complaint_draft": {
                        "location": "안방 천장",
                        "symptom": "물이 샌다",
                        "occurred_at": "2026-09-23T00:00:00"
                      },
                      "missing_fields": ["location"],
                      "citations": []
                    }
                  }
                }
                """.formatted(TURN_ID)), done()));

        AiConverseResponse response = httpAiConverseClient.converse(request(), delta -> { });

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.complaintDraft().toDraft().occurredAt())
            .isEqualTo(OffsetDateTime.parse("2026-09-23T00:00:00+09:00"));
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("AI 응답을 읽지 못하면 응답 원문을 로그에 남기지 않는다")
    void doesNotLogRawBodyWhenResponseIsUnreadable() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(HttpAiConverseClient.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        mockRestServiceServer.expect(requestTo(CONVERSE_STREAM_URL))
            .andRespond(withStream(complete("{\"code\": \"ai_response_success\", \"data\": 12345}"), done()));

        try {
            assertThatThrownBy(() -> httpAiConverseClient.converse(request(), delta -> { }))
                .isInstanceOf(AiUnavailableException.class);
            assertThat(appender.list)
                .anyMatch(event -> event.getFormattedMessage().contains("읽지 못했습니다")
                    && !event.getFormattedMessage().contains("12345"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("AI 요청과 최종 응답 원문을 traceId, turnId와 함께 로그에 남긴다")
    void logsRawRequestAndResponseBodies() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(HttpAiConverseClient.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put("traceId", TRACE_ID);
        mockRestServiceServer.expect(requestTo(CONVERSE_STREAM_URL))
            .andRespond(withStream(complete(complaintReply()), done()));

        try {
            httpAiConverseClient.converse(request(), delta -> { });

            assertThat(appender.list)
                .anyMatch(event -> event.getFormattedMessage()
                    .startsWith("AI 요청 원문. traceId=" + TRACE_ID + ", turnId=" + TURN_ID)
                    && event.getFormattedMessage().contains("\"text\":\"안방 천장 가운데요\""));
            assertThat(appender.list)
                .anyMatch(event -> event.getFormattedMessage()
                    .startsWith("AI 응답 원문. traceId=" + TRACE_ID + ", turnId=" + TURN_ID)
                    && event.getFormattedMessage().contains("아래 내용으로 민원을 접수할까요?"));
        } finally {
            logger.detachAppender(appender);
            MDC.remove("traceId");
        }
    }

    @Test
    @DisplayName("근거가 없는 질의 응답에서 질문 카드를 읽는다")
    void readsQaCardDraftWhenEvidenceIsMissing() {
        mockRestServiceServer.expect(requestTo(CONVERSE_STREAM_URL))
            .andRespond(withStream(complete("""
                {
                  "code": "ai_response_success",
                  "turn_id": "%s",
                  "data": {
                    "route": "knowledge",
                    "next_complaint_state": null,
                    "reply": "근거를 찾지 못했습니다.",
                    "result": {
                      "complaint_draft": null,
                      "qa_card_draft": { "question": "엘리베이터 정기 점검 일정 문의" },
                      "missing_fields": [],
                      "has_sufficient_evidence": false
                    }
                  }
                }
                """.formatted(TURN_ID)), done()));

        AiConverseResponse response = httpAiConverseClient.converse(request(), delta -> { });

        assertThat(response.route()).isEqualTo(AiRoute.KNOWLEDGE);
        assertThat(response.qaCardQuestion()).isEqualTo("엘리베이터 정기 점검 일정 문의");
        assertThat(response.isConversationComplete()).isTrue();
        mockRestServiceServer.verify();
    }

    @Test
    @DisplayName("AI 서버가 요청 제한으로 거절하면 요청 과다 예외로 바꾼다")
    void mapsRateLimitToTooManyRequests() {
        mockRestServiceServer.expect(requestTo(CONVERSE_STREAM_URL))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> httpAiConverseClient.converse(request(), delta -> { }))
            .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    @DisplayName("AI 서버가 응답하지 못하면 AI 사용 불가 예외로 바꾼다")
    void mapsGatewayTimeoutToAiUnavailable() {
        mockRestServiceServer.expect(requestTo(CONVERSE_STREAM_URL))
            .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));

        assertThatThrownBy(() -> httpAiConverseClient.converse(request(), delta -> { }))
            .isInstanceOf(AiUnavailableException.class);
    }

    private static ResponseCreator withStream(String... events) {
        StringBuilder body = new StringBuilder();
        for (String event : events) {
            body.append("data: ").append(event).append("\n\n");
        }
        return withSuccess(body.toString(), MediaType.TEXT_EVENT_STREAM);
    }

    private static String status() {
        return "{\"type\":\"status\",\"status\":\"응답을 준비하고 있습니다.\"}";
    }

    private static String delta(String text) {
        return "{\"type\":\"delta\",\"field\":\"reply\",\"text\":\"" + text + "\"}";
    }

    private static String complete(String response) {
        return "{\"type\":\"complete\",\"response\":" + JSON_MAPPER.readTree(response) + "}";
    }

    private static String done() {
        return "{\"type\":\"done\"}";
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String complaintReply() {
        return """
            {
              "code": "ai_response_success",
              "turn_id": "%s",
              "data": {
                "route": "complaint",
                "complaint_intent": "register",
                "next_complaint_state": null,
                "reply": "아래 내용으로 민원을 접수할까요?",
                "result": {
                  "complaint_draft": {
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
            """.formatted(TURN_ID);
    }

    private AiConverseRequest request() {
        return new AiConverseRequest(
            1L, "302", "7", "11", TURN_ID, TRACE_ID,
            AiRoute.COMPLAINT, AiComplaintState.COLLECTING,
            new AiConverseRequest.MessagePayload("21", "안방 천장 가운데요",
                List.of(new AiConverseRequest.MessageImage(32L, "https://s3.test/ceiling.jpg"))),
            List.of(
                new AiConverseRequest.HistoryMessage("19", AiTurnRole.USER, "천장에서 물이 새요",
                    List.of(new AiConverseRequest.HistoryImage(31L, "천장 얼룩", "관리실 010"))),
                new AiConverseRequest.HistoryMessage("20", AiTurnRole.ASSISTANT, "위치가 어디인가요?", null)),
            AiConverseRequest.ComplaintDraftPayload.builder()
                .issueType("leak")
                .symptom("천장에서 물이 새요")
                .attachmentIds(List.of(31L))
                .build());
    }
}
