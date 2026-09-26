package com.homes.zipsai.conversation;

import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ConversationTestFixture.class)
class ResidentConversationApiTest {

    private static final String CONVERSATIONS = "/api/v1/residents/me/conversations";
    private static final String COMPLAINTS = "/api/v1/residents/me/complaints";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    ConversationTestFixture fixture;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        token = fixture.login(mvc, json, fixture.livingResident("302"));
    }

    @Test
    void chatCollectsComplaintInfoAndCreatesComplaintOnce() throws Exception {
        long conversationId = startConversation(token, "천장에서 물이 새요")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.conversationType").value("INQUIRY"))
            .andExpect(jsonPath("$.data.conversationStatus").value("ACTIVE"))
            .andExpect(jsonPath("$.data.message.senderType").value("RESIDENT"))
            .andExpect(jsonPath("$.data.assistantMessage.messageType").value("TEXT"))
            .andExpect(jsonPath("$.data.assistantMessage.summaryCard").doesNotExist())
            .andReturn().getResponse().getContentAsString().transform(this::conversationId);

        sendMessage(token, conversationId, "안방 천장 가운데요")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.content").value("안방 천장 가운데요"))
            .andExpect(jsonPath("$.data.assistantMessage.messageType").value("SUMMARY_CARD"))
            .andExpect(jsonPath("$.data.assistantMessage.summaryCard.location").value("안방 천장 가운데요"))
            .andExpect(jsonPath("$.data.assistantMessage.summaryCard.symptom").value("천장에서 물이 새요"))
            .andExpect(jsonPath("$.data.assistantMessage.summaryCard.occurredTime").value(nullValue()));

        mvc.perform(get(CONVERSATIONS + "/" + conversationId + "/messages").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.messages[2].summaryCard").doesNotExist())
            .andExpect(jsonPath("$.data.messages[3].messageType").value("SUMMARY_CARD"))
            .andExpect(jsonPath("$.data.messages[3].summaryCard.location").value("안방 천장 가운데요"))
            .andExpect(jsonPath("$.data.messages[3].summaryCard.symptom").value("천장에서 물이 새요"));

        sendMessage(token, conversationId, "네 접수해주세요")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONVERSATION_AWAITING_CONFIRMATION"))
            .andExpect(jsonPath("$.error.details.field").value("conversationId"));

        mvc.perform(get(CONVERSATIONS + "/" + conversationId + "/messages").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.messages.length()").value(4))
            .andExpect(jsonPath("$.data.messages[0].content").value("천장에서 물이 새요"))
            .andExpect(jsonPath("$.data.hasNext").value(false))
            .andExpect(jsonPath("$.data.complaintId").value(nullValue()));

        String complaint = """
            {"conversationId":%d,"occurredTime":"2026-09-15T20:00:00+09:00"}
            """.formatted(conversationId);
        createComplaint(token, complaint)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.conversationId").value(conversationId))
            .andExpect(jsonPath("$.data.title").value("천장에서 물이 새요"))
            .andExpect(jsonPath("$.data.location").value("안방 천장 가운데요"))
            .andExpect(jsonPath("$.data.symptom").value("천장에서 물이 새요"))
            .andExpect(jsonPath("$.data.occurredTime").value(startsWith("2026-09-15T20:00")))
            .andExpect(jsonPath("$.data.statusCode").value("PENDING"))
            .andExpect(jsonPath("$.data.statusLabel").value("처리전"))
            .andExpect(jsonPath("$.data.buildingName").value("테스트타워"))
            .andExpect(jsonPath("$.data.roomNo").value("302"));
        createComplaint(token, complaint)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("COMPLAINT_ALREADY_CREATED"));

        mvc.perform(get(CONVERSATIONS + "/" + conversationId + "/messages").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.conversationStatus").value("COMPLAINT_CREATED"))
            .andExpect(jsonPath("$.data.statusCode").doesNotExist())
            .andExpect(jsonPath("$.data.conversationType").doesNotExist())
            .andExpect(jsonPath("$.data.statusLabel").value("민원 생성 완료"))
            .andExpect(jsonPath("$.data.messages.length()").value(4))
            .andExpect(jsonPath("$.data.messages[3].messageType").value("SUMMARY_CARD"))
            .andExpect(jsonPath("$.data.messages[3].summaryCard.occurredTime").value(startsWith("2026-09-15T20:00")));

        sendMessage(token, conversationId, "추가 문의요")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONVERSATION_CLOSED"));
        mvc.perform(get(CONVERSATIONS).header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.conversations.length()").value(1))
            .andExpect(jsonPath("$.data.conversations[0].conversationType").value("COMPLAINT"))
            .andExpect(jsonPath("$.data.conversations[0].statusCode").value("COMPLAINT_CREATED"))
            .andExpect(jsonPath("$.data.conversations[0].statusLabel").value("민원 생성 완료"));
    }

    @Test
    void inquiryGetsGuideAnswer() throws Exception {
        startConversation(token, "분리수거 요일이 언제인가요?")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.assistantMessage.messageType").value("TEXT"))
            .andExpect(jsonPath("$.data.assistantMessage.content").isNotEmpty());
    }

    @Test
    void complaintBeforeSummaryCardIsRejected() throws Exception {
        long conversationId = conversationId(startConversation(token, "현관 조명이 꺼졌어요")
            .andReturn().getResponse().getContentAsString());

        createComplaint(token, "{\"conversationId\":%d,\"symptom\":\"현관 조명 꺼짐\"}".formatted(conversationId))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("COMPLAINT_NOT_READY"))
            .andExpect(jsonPath("$.error.details.field").value("conversationId"));
    }

    @Test
    void otherResidentsConversationIsForbiddenAndUnknownIsNotFound() throws Exception {
        long conversationId = conversationId(startConversation(token, "천장에서 물이 새요")
            .andReturn().getResponse().getContentAsString());
        String otherToken = fixture.login(mvc, json, fixture.livingResident("101"));

        mvc.perform(get(CONVERSATIONS + "/" + conversationId + "/messages").header("Authorization", bearer(otherToken)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        sendMessage(otherToken, conversationId, "끼어들기").andExpect(status().isForbidden());
        createComplaint(otherToken, "{\"conversationId\":%d}".formatted(conversationId))
            .andExpect(status().isForbidden());
        sendMessage(token, 999_999L, "없는 대화")
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("CONVERSATION_NOT_FOUND"))
            .andExpect(jsonPath("$.error.details.field").value("conversationId"));
    }

    @Test
    void residentWithoutRoomCannotStartConversation() throws Exception {
        String unconnected = fixture.login(mvc, json, fixture.unconnectedResident());
        startConversation(unconnected, "천장에서 물이 새요")
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(get(CONVERSATIONS).header("Authorization", bearer(unconnected)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.conversations.length()").value(0))
            .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void validatesRequestBodyPathAndQuery() throws Exception {
        startConversation(token, "   ")
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.details.violations[0].field").value("contentOrImagePresent"))
            .andExpect(jsonPath("$.data").value(nullValue()));
        startConversation(token, "가".repeat(201))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.details.violations[0].reason").value("메시지는 200자 이하여야 합니다."));
        sendMessage(token, 0L, "안녕하세요")
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.error.details.violations[0].field").value("conversationId"));
        mvc.perform(get(CONVERSATIONS + "/abc/messages").header("Authorization", bearer(token)))
            .andExpect(status().isUnprocessableContent());
        mvc.perform(get(CONVERSATIONS).param("size", "abc").header("Authorization", bearer(token)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAMETER"));
        mvc.perform(get(CONVERSATIONS).param("size", "101").header("Authorization", bearer(token)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details.violations[0].field").value("size"));
        mvc.perform(get(CONVERSATIONS).param("cursor", "not-a-cursor").header("Authorization", bearer(token)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_QUERY_PARAMETER"))
            .andExpect(jsonPath("$.error.details.violations[0].field").value("cursor"));
        sendMessage(token, 1L, "   ")
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.details.violations[0].field").value("contentOrImagePresent"));
        createComplaint(token, "{}")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.details.violations[0].field").value("conversationId"));
        createComplaint(token, "{\"conversationId\":1,\"location\":\"%s\"}".formatted("가".repeat(51)))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.error.details.violations[0].reason").value("발생 위치는 50자 이하여야 합니다."));
    }

    @Test
    void messagesArePagedByCursorFromLatest() throws Exception {
        long conversationId = conversationId(startConversation(token, "천장에서 물이 새요")
            .andReturn().getResponse().getContentAsString());
        sendMessage(token, conversationId, "안방이요");

        String firstPage = mvc.perform(get(CONVERSATIONS + "/" + conversationId + "/messages")
                .param("size", "3").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.hasNext").value(true))
            .andExpect(jsonPath("$.data.messages.length()").value(3))
            .andExpect(jsonPath("$.data.messages[2].senderType").value("ASSISTANT"))
            .andReturn().getResponse().getContentAsString();
        long cursor = json.readTree(firstPage).path("data").path("nextCursor").asLong();

        mvc.perform(get(CONVERSATIONS + "/" + conversationId + "/messages")
                .param("size", "3").param("cursor", Long.toString(cursor)).header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.hasNext").value(false))
            .andExpect(jsonPath("$.data.nextCursor").value(nullValue()))
            .andExpect(jsonPath("$.data.messages.length()").value(1))
            .andExpect(jsonPath("$.data.messages[0].content").value("천장에서 물이 새요"));
    }

    @Test
    void conversationsArePagedByCursorFromLatest() throws Exception {
        startConversation(token, "분리수거 요일이 언제인가요?");
        startConversation(token, "주차 등록은 어떻게 하나요?");
        startConversation(token, "택배 보관함은 어디 있나요?");

        String firstPage = mvc.perform(get(CONVERSATIONS).param("size", "2").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.hasNext").value(true))
            .andExpect(jsonPath("$.data.conversations.length()").value(2))
            .andExpect(jsonPath("$.data.conversations[0].conversationTitle").value("택배 보관함은 어디 있나요?"))
            .andExpect(jsonPath("$.data.conversations[1].conversationTitle").value("주차 등록은 어떻게 하나요?"))
            .andReturn().getResponse().getContentAsString();
        String cursor = json.readTree(firstPage).path("data").path("nextCursor").asText();

        mvc.perform(get(CONVERSATIONS).param("size", "2").param("cursor", cursor)
                .header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.hasNext").value(false))
            .andExpect(jsonPath("$.data.nextCursor").value(nullValue()))
            .andExpect(jsonPath("$.data.conversations.length()").value(1))
            .andExpect(jsonPath("$.data.conversations[0].conversationTitle").value("분리수거 요일이 언제인가요?"));

        mvc.perform(get(CONVERSATIONS).param("keyword", "주차").header("Authorization", bearer(token)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.conversations.length()").value(1))
            .andExpect(jsonPath("$.data.conversations[0].conversationTitle").value("주차 등록은 어떻게 하나요?"));
    }

    private ResultActions startConversation(String accessToken, String content) throws Exception {
        return mvc.perform(post(CONVERSATIONS).header("Authorization", bearer(accessToken))
            .contentType("application/json").content(json.writeValueAsString(new ContentBody(content))));
    }

    private ResultActions sendMessage(String accessToken, long conversationId, String content) throws Exception {
        return mvc.perform(post(CONVERSATIONS + "/" + conversationId + "/messages")
            .header("Authorization", bearer(accessToken))
            .contentType("application/json").content(json.writeValueAsString(new ContentBody(content))));
    }

    private ResultActions createComplaint(String accessToken, String body) throws Exception {
        return mvc.perform(post(COMPLAINTS).header("Authorization", bearer(accessToken))
            .contentType("application/json").content(body));
    }

    private long conversationId(String responseBody) {
        return json.readTree(responseBody).path("data").path("conversationId").asLong();
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private record ContentBody(String content) {
    }
}
