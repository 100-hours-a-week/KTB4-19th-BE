package com.homes.zipsai.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasLength;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.homes.zipsai.conversation.ai.AiComplaintDraft;
import com.homes.zipsai.conversation.ai.AiConversationState;
import com.homes.zipsai.conversation.ai.AiConverseClient;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.conversation.repository.ConversationRepository;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ConversationTestFixture.class)
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
class ConversationMockAiTest {

    private static final String CONVERSATIONS = "/api/v1/residents/me/conversations";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    ConversationTestFixture fixture;

    @Autowired
    ConversationRepository conversationRepository;

    @MockitoBean
    AiConverseClient aiConverseClient;

    @Test
    void firstMessageFailureDoesNotLeaveConversation() throws Exception {
        String token = fixture.login(mvc, json, fixture.livingResident("302"));
        long before = conversationRepository.count();
        given(aiConverseClient.converse(any())).willThrow(new IllegalStateException("AI timeout"));

        mvc.perform(post(CONVERSATIONS).header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"content\":\"천장에서 물이 새요\"}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"));

        assertThat(conversationRepository.count()).isEqualTo(before);
    }

    @Test
    void messageWhileAiIsRespondingIsRejectedAsBusy() throws Exception {
        String token = fixture.login(mvc, json, fixture.livingResident("302"));
        AiConverseResponse reply =
            new AiConverseResponse(AiRoute.COMPLAINT, AiConversationState.COLLECTING, "위치가 어디인가요?", null);
        CountDownLatch aiEntered = new CountDownLatch(1);
        CountDownLatch releaseAi = new CountDownLatch(1);
        given(aiConverseClient.converse(any()))
            .willReturn(reply)
            .willAnswer(invocation -> {
                aiEntered.countDown();
                releaseAi.await(5, TimeUnit.SECONDS);
                return reply;
            });
        long conversationId = startConversation(token);

        CompletableFuture<Integer> firstSend = CompletableFuture.supplyAsync(() -> sendStatus(token, conversationId));
        assertThat(aiEntered.await(5, TimeUnit.SECONDS)).isTrue();
        mvc.perform(post(CONVERSATIONS + "/" + conversationId + "/messages").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"content\":\"두 번째 메시지\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONVERSATION_BUSY"));
        releaseAi.countDown();

        assertThat(firstSend.get(5, TimeUnit.SECONDS)).isEqualTo(201);
    }

    @Test
    void followUpFailureRemovesOnlyUnansweredMessage() throws Exception {
        String token = fixture.login(mvc, json, fixture.livingResident("302"));
        given(aiConverseClient.converse(any()))
            .willReturn(new AiConverseResponse(AiRoute.COMPLAINT, AiConversationState.COLLECTING, "위치가 어디인가요?", null))
            .willThrow(new IllegalStateException("AI timeout"));
        String created = mvc.perform(post(CONVERSATIONS).header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"content\":\"천장에서 물이 새요\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        long conversationId = json.readTree(created).path("data").path("conversationId").asLong();

        mvc.perform(post(CONVERSATIONS + "/" + conversationId + "/messages").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"content\":\"안방이요\"}"))
            .andExpect(status().isInternalServerError());

        mvc.perform(get(CONVERSATIONS + "/" + conversationId + "/messages").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.messages.length()").value(2));
    }

    @Test
    void aiReplyLongerThanMessageLimitIsTrimmed() throws Exception {
        String token = fixture.login(mvc, json, fixture.livingResident("302"));
        given(aiConverseClient.converse(any())).willReturn(
            new AiConverseResponse(AiRoute.KNOWLEDGE, null, "가".repeat(900), null));

        mvc.perform(post(CONVERSATIONS).header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"content\":\"분리수거 요일이 언제인가요?\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.assistantMessage.content").value(hasLength(800)));
    }

    @Test
    void conversationResolvedWhileAiIsRespondingStaysClosed() throws Exception {
        String token = fixture.login(mvc, json, fixture.livingResident("302"));
        AiComplaintDraft draft = new AiComplaintDraft("안방 천장", "천장에서 물이 새요", OffsetDateTime.now());
        CountDownLatch aiEntered = new CountDownLatch(1);
        CountDownLatch releaseAi = new CountDownLatch(1);
        given(aiConverseClient.converse(any()))
            .willReturn(new AiConverseResponse(AiRoute.COMPLAINT, AiConversationState.COLLECTING, "위치가 어디인가요?", null))
            .willAnswer(invocation -> {
                aiEntered.countDown();
                releaseAi.await(5, TimeUnit.SECONDS);
                return new AiConverseResponse(AiRoute.COMPLAINT, AiConversationState.READY_TO_CONFIRM, "접수할까요?", draft);
            });
        long conversationId = startConversation(token);

        CompletableFuture<Integer> send = CompletableFuture.supplyAsync(() -> sendStatus(token, conversationId));
        assertThat(aiEntered.await(5, TimeUnit.SECONDS)).isTrue();
        mvc.perform(patch(CONVERSATIONS + "/" + conversationId).header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"conversationStatus\":\"RESOLVED\"}"))
            .andExpect(status().isOk());
        releaseAi.countDown();
        assertThat(send.get(5, TimeUnit.SECONDS)).isEqualTo(201);

        mvc.perform(get(CONVERSATIONS + "/" + conversationId + "/messages").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.conversationStatus").value("RESOLVED"))
            .andExpect(jsonPath("$.data.messages[3].messageType").value("TEXT"));
    }

    private long startConversation(String token) throws Exception {
        String created = mvc.perform(post(CONVERSATIONS).header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"content\":\"천장에서 물이 새요\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(created).path("data").path("conversationId").asLong();
    }

    private int sendStatus(String token, long conversationId) {
        try {
            return mvc.perform(post(CONVERSATIONS + "/" + conversationId + "/messages")
                    .header("Authorization", "Bearer " + token)
                    .contentType("application/json").content("{\"content\":\"안방이요\"}"))
                .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
