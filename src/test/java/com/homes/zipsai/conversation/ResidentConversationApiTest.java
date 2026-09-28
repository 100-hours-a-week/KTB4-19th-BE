package com.homes.zipsai.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ConversationTestFixture.class)
class ResidentConversationApiTest {

    private static final String CONVERSATIONS = "/api/v1/residents/me/conversations";
    private static final String COMPLAINTS = "/api/v1/residents/me/complaints";

    @Autowired
    MockMvcTester mockMvcTester;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ConversationTestFixture conversationTestFixture;

    private RequestPostProcessor resident;

    @BeforeEach
    void setUp() {
        resident = conversationTestFixture.authenticatedAs(conversationTestFixture.livingResident("302"));
    }

    @Test
    @DisplayName("대화로 민원 정보를 모아 한 번만 접수하고 접수 뒤에는 대화가 닫힌다")
    void createsComplaintOnceFromConversation() throws Exception {
        MvcTestResult started = startConversation(resident, "천장에서 물이 새요");
        assertThat(started).hasStatus(HttpStatus.CREATED)
            .bodyJson().doesNotHavePath("$.data.assistantMessage");
        long conversationId = conversationId(started);

        assertThat(getMessages(resident, conversationId)).hasStatusOk()
            .bodyJson().isLenientlyEqualTo("""
                {"data": {"conversationStatus": "ACTIVE",
                          "messages": [{"senderType": "RESIDENT"},
                                       {"senderType": "ASSISTANT", "messageType": "TEXT"}]}}
                """);

        assertThat(sendMessage(resident, conversationId, "안방 천장 가운데요")).hasStatus(HttpStatus.CREATED)
            .bodyJson().isLenientlyEqualTo("""
                {"data": {"content": "안방 천장 가운데요",
                          "assistantMessage": {"messageType": "SUMMARY_CARD",
                                               "summaryCard": {"location": "안방 천장 가운데요",
                                                               "symptom": "천장에서 물이 새요",
                                                               "occurredTime": null}}}}
                """);

        assertThat(sendMessage(resident, conversationId, "네 접수해주세요")).hasStatus(HttpStatus.CONFLICT)
            .bodyJson().isLenientlyEqualTo("""
                {"error": {"code": "CONVERSATION_AWAITING_CONFIRMATION", "details": {"field": "conversationId"}}}
                """);

        String complaint = """
            {"conversationId": %d, "occurredTime": "2026-09-15T20:00:00+09:00"}
            """.formatted(conversationId);
        MvcTestResult created = createComplaint(resident, complaint);
        assertThat(created).hasStatus(HttpStatus.CREATED)
            .bodyJson().isLenientlyEqualTo("""
                {"data": {"conversationId": %d, "title": "천장에서 물이 새요", "location": "안방 천장 가운데요",
                          "symptom": "천장에서 물이 새요", "statusCode": "PENDING", "statusLabel": "처리전",
                          "buildingName": "테스트타워", "roomNo": "302"}}
                """.formatted(conversationId));
        assertThat(created).bodyJson().extractingPath("$.data.occurredTime").asString().startsWith("2026-09-15T20:00");

        assertThat(createComplaint(resident, complaint)).hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.error.code").isEqualTo("COMPLAINT_ALREADY_CREATED");

        MvcTestResult closed = getMessages(resident, conversationId);
        assertThat(closed).hasStatusOk()
            .bodyJson()
            .doesNotHavePath("$.data.statusCode")
            .doesNotHavePath("$.data.conversationType")
            .isLenientlyEqualTo("""
                {"data": {"conversationStatus": "COMPLAINT_CREATED", "statusLabel": "민원 생성 완료",
                          "messages": [{}, {}, {}, {"messageType": "SUMMARY_CARD"}]}}
                """);
        assertThat(closed).bodyJson().extractingPath("$.data.messages[3].summaryCard.occurredTime")
            .asString().startsWith("2026-09-15T20:00");

        assertThat(sendMessage(resident, conversationId, "추가 문의요")).hasStatus(HttpStatus.CONFLICT)
            .bodyJson().extractingPath("$.error.code").isEqualTo("CONVERSATION_CLOSED");
        assertThat(mockMvcTester.get().uri(CONVERSATIONS).with(resident)).hasStatusOk()
            .bodyJson().isLenientlyEqualTo("""
                {"data": {"conversations": [{"conversationType": "COMPLAINT", "statusCode": "COMPLAINT_CREATED",
                                             "statusLabel": "민원 생성 완료"}]}}
                """);
    }

    @Test
    @DisplayName("호실에 연결되지 않은 입주민은 대화를 시작할 수 없다")
    void rejectsResidentWithoutRoom() {
        RequestPostProcessor unconnected =
            conversationTestFixture.authenticatedAs(conversationTestFixture.unconnectedResident());

        assertThat(startConversation(unconnected, "천장에서 물이 새요")).hasStatus(HttpStatus.FORBIDDEN)
            .bodyJson().extractingPath("$.error.code").isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("경로의 대화 ID가 양수가 아니면 거절한다")
    void rejectsNonPositiveConversationIdInPath() {
        assertThat(sendMessage(resident, 0L, "안녕하세요")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
            .bodyJson().extractingPath("$.error.details.violations[0].field").isEqualTo("conversationId");
    }

    @Test
    @DisplayName("경로의 대화 ID가 숫자가 아니면 거절한다")
    void rejectsNonNumericConversationIdInPath() {
        assertThat(mockMvcTester.get().uri(CONVERSATIONS + "/abc/messages").with(resident))
            .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    @DisplayName("대화 목록 조회 개수가 숫자가 아니면 거절한다")
    void rejectsNonNumericPageSize() {
        assertThat(mockMvcTester.get().uri(CONVERSATIONS).param("size", "abc").with(resident))
            .hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson().extractingPath("$.error.code").isEqualTo("INVALID_QUERY_PARAMETER");
    }

    @Test
    @DisplayName("대화 목록 조회 개수가 100을 넘으면 거절한다")
    void rejectsPageSizeOver100() {
        assertThat(mockMvcTester.get().uri(CONVERSATIONS).param("size", "101").with(resident))
            .hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson().extractingPath("$.error.details.violations[0].field").isEqualTo("size");
    }

    @Test
    @DisplayName("대화 목록 커서 형식이 잘못되면 거절한다")
    void rejectsMalformedCursor() {
        assertThat(mockMvcTester.get().uri(CONVERSATIONS).param("cursor", "not-a-cursor").with(resident))
            .hasStatus(HttpStatus.BAD_REQUEST)
            .bodyJson().isLenientlyEqualTo("""
                {"error": {"code": "INVALID_QUERY_PARAMETER", "details": {"violations": [{"field": "cursor"}]}}}
                """);
    }

    private MvcTestResult startConversation(RequestPostProcessor authentication, String content) {
        return mockMvcTester.post().uri(CONVERSATIONS).with(authentication)
            .contentType(MediaType.APPLICATION_JSON).content(messageBody(content))
            .exchange();
    }

    private MvcTestResult sendMessage(RequestPostProcessor authentication, long conversationId, String content) {
        return mockMvcTester.post().uri(CONVERSATIONS + "/{id}/messages", conversationId).with(authentication)
            .contentType(MediaType.APPLICATION_JSON).content(messageBody(content))
            .exchange();
    }

    private MvcTestResult getMessages(RequestPostProcessor authentication, long conversationId) {
        return mockMvcTester.get().uri(CONVERSATIONS + "/{id}/messages", conversationId).with(authentication)
            .exchange();
    }

    private MvcTestResult createComplaint(RequestPostProcessor authentication, String body) {
        return mockMvcTester.post().uri(COMPLAINTS).with(authentication)
            .contentType(MediaType.APPLICATION_JSON).content(body)
            .exchange();
    }

    private String messageBody(String content) {
        return objectMapper.writeValueAsString(Map.of("content", content));
    }

    private long conversationId(MvcTestResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString())
            .path("data").path("conversationId").asLong();
    }
}
