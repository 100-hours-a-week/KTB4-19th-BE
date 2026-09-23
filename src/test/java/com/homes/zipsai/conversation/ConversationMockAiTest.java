package com.homes.zipsai.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.ai.AiComplaintDraft;
import com.homes.zipsai.conversation.ai.AiComplaintState;
import com.homes.zipsai.conversation.ai.AiConverseClient;
import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.conversation.repository.MessageRepository;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ConversationTestFixture.class)
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
class ConversationMockAiTest {

    private static final String CONVERSATIONS = "/api/v1/residents/me/conversations";
    private static final String COMPLAINTS = "/api/v1/residents/me/complaints";
    private static final List<AiConverseResponse.Citation> CITATIONS = List.of(
        new AiConverseResponse.Citation("building_document", "building-guide-12", "생활 안내", null, null));

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    ConversationTestFixture fixture;

    @Autowired
    ConversationRepository conversationRepository;

    @Autowired
    MessageRepository messageRepository;

    @MockitoBean
    AiConverseClient aiConverseClient;

    @MockitoBean
    S3StorageService s3StorageService;

    @BeforeEach
    void setUp() {
        given(s3StorageService.prepareDownload(anyString(), any()))
            .willAnswer(invocation ->
                new S3StorageService.PresignedDownload("https://s3.test/" + invocation.getArgument(0)));
    }

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
    void replyWithAnotherTraceIdIsRejected() throws Exception {
        String token = fixture.login(mvc, json, fixture.livingResident("302"));
        long before = conversationRepository.count();
        given(aiConverseClient.converse(any()))
            .willReturn(knowledge("남의-추적-아이디", "분리수거는 화요일과 금요일입니다.", CITATIONS));

        mvc.perform(post(CONVERSATIONS).header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"content\":\"분리수거 요일이 언제인가요?\"}"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"));

        assertThat(conversationRepository.count()).isEqualTo(before);
    }

    @Test
    void residentMessageAndReplyShareOneTraceId() throws Exception {
        String token = fixture.login(mvc, json, fixture.livingResident("302"));
        given(aiConverseClient.converse(any())).willAnswer(ConversationMockAiTest::collecting);
        long conversationId = startConversation(token);

        List<Message> messages = messageRepository.findAllByConversationId(conversationId);

        assertThat(messages).hasSize(2);
        assertThat(messages.getFirst().getSenderType()).isEqualTo(SenderType.RESIDENT);
        assertThat(messages.getLast().getSenderType()).isEqualTo(SenderType.ASSISTANT);
        assertThat(messages.getFirst().getTraceId())
            .isNotBlank()
            .isEqualTo(messages.getLast().getTraceId());
    }

    @Test
    void messageWhileAiIsRespondingIsRejectedAsBusy() throws Exception {
        String token = fixture.login(mvc, json, fixture.livingResident("302"));
        CountDownLatch aiEntered = new CountDownLatch(1);
        CountDownLatch releaseAi = new CountDownLatch(1);
        given(aiConverseClient.converse(any()))
            .willAnswer(ConversationMockAiTest::collecting)
            .willAnswer(invocation -> {
                aiEntered.countDown();
                releaseAi.await(5, TimeUnit.SECONDS);
                return collecting(invocation);
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
            .willAnswer(ConversationMockAiTest::collecting)
            .willThrow(new IllegalStateException("AI timeout"));
        long conversationId = startConversation(token);

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
        given(aiConverseClient.converse(any())).willAnswer(invocation ->
            knowledge(request(invocation).traceId(), "가".repeat(900), CITATIONS));

        mvc.perform(post(CONVERSATIONS).header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"content\":\"분리수거 요일이 언제인가요?\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.assistantMessage.content").value(hasLength(800)));
    }

    @Test
    void knowledgeWithoutEvidenceIsRegisteredAsQaCardComplaint() throws Exception {
        String token = fixture.login(mvc, json, fixture.livingResident("302"));
        given(aiConverseClient.converse(any())).willAnswer(invocation -> qaCard(
            request(invocation).traceId(), AiRoute.KNOWLEDGE,
            "건물 문서에서 근거를 찾지 못했습니다. 질문을 관리인에게 전달해 두었습니다.", "엘리베이터 정기 점검 일정 문의"));

        String created = mvc.perform(post(CONVERSATIONS).header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"content\":\"엘리베이터 점검은 언제 하나요?\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.assistantMessage.messageType").value("SUMMARY_CARD"))
            .andExpect(jsonPath("$.data.assistantMessage.summaryCard.symptom").value("엘리베이터 정기 점검 일정 문의"))
            .andReturn().getResponse().getContentAsString();
        long conversationId = json.readTree(created).path("data").path("conversationId").asLong();

        mvc.perform(post(CONVERSATIONS + "/" + conversationId + "/messages").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"content\":\"그럼 언제 알 수 있나요?\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONVERSATION_AWAITING_CONFIRMATION"));

        mvc.perform(post(COMPLAINTS).header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"conversationId\":%d}".formatted(conversationId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.title").value("엘리베이터 정기 점검 일정 문의"))
            .andExpect(jsonPath("$.data.symptom").value("엘리베이터 정기 점검 일정 문의"))
            .andExpect(jsonPath("$.data.location").value("미상"));
    }

    @Test
    void knowledgeWithoutCitationsEndsTheConversation() throws Exception {
        String token = fixture.login(mvc, json, fixture.livingResident("302"));
        given(aiConverseClient.converse(any())).willAnswer(invocation ->
            knowledge(request(invocation).traceId(), "답변드리기 어렵습니다.", List.of()));

        String created = mvc.perform(post(CONVERSATIONS).header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"content\":\"택배 보관함은 어디 있나요?\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.assistantMessage.messageType").value("SUMMARY_CARD"))
            .andReturn().getResponse().getContentAsString();
        long conversationId = json.readTree(created).path("data").path("conversationId").asLong();

        mvc.perform(post(CONVERSATIONS + "/" + conversationId + "/messages").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"content\":\"그럼 어디로 가야 하나요?\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("CONVERSATION_AWAITING_CONFIRMATION"));
    }

    @Test
    void knowledgeWithCitationsKeepsTheConversationOpen() throws Exception {
        String token = fixture.login(mvc, json, fixture.livingResident("302"));
        given(aiConverseClient.converse(any())).willAnswer(invocation ->
            knowledge(request(invocation).traceId(), "화요일과 금요일입니다.", CITATIONS));

        String created = mvc.perform(post(CONVERSATIONS).header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"content\":\"분리수거 요일이 언제인가요?\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.assistantMessage.messageType").value("TEXT"))
            .andReturn().getResponse().getContentAsString();
        long conversationId = json.readTree(created).path("data").path("conversationId").asLong();

        mvc.perform(post(CONVERSATIONS + "/" + conversationId + "/messages").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"content\":\"몇 시까지인가요?\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.assistantMessage.messageType").value("TEXT"));
    }

    @Test
    void conversationResolvedWhileAiIsRespondingStaysClosed() throws Exception {
        String token = fixture.login(mvc, json, fixture.livingResident("302"));
        AiComplaintDraft draft =
            new AiComplaintDraft("안방 천장", "천장에서 물이 새요", OffsetDateTime.now());
        CountDownLatch aiEntered = new CountDownLatch(1);
        CountDownLatch releaseAi = new CountDownLatch(1);
        given(aiConverseClient.converse(any()))
            .willAnswer(ConversationMockAiTest::collecting)
            .willAnswer(invocation -> {
                aiEntered.countDown();
                releaseAi.await(5, TimeUnit.SECONDS);
                return complaint(request(invocation).traceId(), null, "접수할까요?", draft, List.of());
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

    @Test
    @DisplayName("사진을 첨부한 메시지가 저장되고 AI에 사진 URL이 전달된다")
    void savesAttachedImagesAndSendsUrlsToAi() throws Exception {
        String email = fixture.livingResident("302");
        String token = fixture.login(mvc, json, email);
        long firstImage = fixture.uploadedFile(email, "jpg");
        long secondImage = fixture.uploadedFile(email, "png");
        given(aiConverseClient.converse(any())).willAnswer(ConversationMockAiTest::collecting);

        String created = mvc.perform(post(CONVERSATIONS).header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"content\":\"천장에서 물이 새요\",\"attachmentIds\":[%d]}".formatted(firstImage)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.message.attachments.length()").value(1))
            .andExpect(jsonPath("$.data.message.attachments[0].attachmentId").value(firstImage))
            .andExpect(jsonPath("$.data.message.attachments[0].fileUrl").value(startsWith("https://s3.test/")))
            .andExpect(jsonPath("$.data.message.attachments[0].seq").value(1))
            .andReturn().getResponse().getContentAsString();
        long conversationId = json.readTree(created).path("data").path("conversationId").asLong();

        mvc.perform(post(CONVERSATIONS + "/" + conversationId + "/messages").header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"content\":\"안방이요\",\"attachmentIds\":[%d]}".formatted(secondImage)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.attachments[0].attachmentId").value(secondImage));

        ArgumentCaptor<AiConverseRequest> requests = ArgumentCaptor.forClass(AiConverseRequest.class);
        then(aiConverseClient).should(times(2)).converse(requests.capture());
        assertThat(requests.getAllValues().getFirst().message().imageUrls()).hasSize(1);
        AiConverseRequest followUp = requests.getAllValues().getLast();
        assertThat(followUp.message().imageUrls()).hasSize(1);
        assertThat(followUp.conversationHistory().getFirst().imageUrls()).hasSize(1);
        assertThat(followUp.conversationHistory().getLast().imageUrls()).isEmpty();

        mvc.perform(get(CONVERSATIONS + "/" + conversationId + "/messages").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.messages[0].attachments[0].attachmentId").value(firstImage))
            .andExpect(jsonPath("$.data.messages[1].attachments.length()").value(0))
            .andExpect(jsonPath("$.data.messages[2].attachments[0].attachmentId").value(secondImage));
    }

    @Test
    @DisplayName("사진만 첨부하고 내용 없이 메시지를 보낼 수 있다")
    void sendsImageOnlyMessage() throws Exception {
        String email = fixture.livingResident("302");
        String token = fixture.login(mvc, json, email);
        long image = fixture.uploadedFile(email, "jpg");
        given(aiConverseClient.converse(any())).willAnswer(ConversationMockAiTest::collecting);

        mvc.perform(post(CONVERSATIONS).header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"content\":\"\",\"attachmentIds\":[%d]}".formatted(image)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.conversationTitle").value("사진 문의"))
            .andExpect(jsonPath("$.data.message.content").value(""))
            .andExpect(jsonPath("$.data.message.attachments[0].attachmentId").value(image));
    }

    @Test
    @DisplayName("AI 호출이 실패하면 답변받지 못한 메시지의 사진 연결도 지운다")
    void removesImageLinksOfUnansweredMessageWhenAiFails() throws Exception {
        String email = fixture.livingResident("302");
        String token = fixture.login(mvc, json, email);
        long image = fixture.uploadedFile(email, "jpg");
        long before = conversationRepository.count();
        given(aiConverseClient.converse(any()))
            .willThrow(new IllegalStateException("AI timeout"))
            .willAnswer(ConversationMockAiTest::collecting);

        startConversationWith(token, image)
            .andExpect(status().isInternalServerError());
        assertThat(conversationRepository.count()).isEqualTo(before);

        startConversationWith(token, image)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.message.attachments[0].attachmentId").value(image));
        assertThat(conversationRepository.count()).isEqualTo(before + 1);
    }

    private static AiConverseResponse collecting(InvocationOnMock invocation) {
        return complaint(request(invocation).traceId(), AiComplaintState.COLLECTING,
            "위치가 어디인가요?", null, List.of("location"));
    }

    private static AiConverseRequest request(InvocationOnMock invocation) {
        return invocation.getArgument(0);
    }

    private static AiConverseResponse complaint(String traceId, AiComplaintState nextComplaintState, String reply,
                                                AiComplaintDraft draft, List<String> missingFields) {
        AiConverseResponse.DraftPatch patch = draft == null
            ? null
            : new AiConverseResponse.DraftPatch(draft.location(), draft.symptom(),
                draft.occurredAt() == null ? null : draft.occurredAt().toString());
        return response(traceId, AiRoute.COMPLAINT, nextComplaintState, reply,
            new AiConverseResponse.Result(patch, null, missingFields, List.of()));
    }

    private static AiConverseResponse knowledge(String traceId, String reply,
                                                List<AiConverseResponse.Citation> citations) {
        return response(traceId, AiRoute.KNOWLEDGE, null, reply,
            new AiConverseResponse.Result(null, null, List.of(), citations));
    }

    private static AiConverseResponse qaCard(String traceId, AiRoute route, String reply, String question) {
        return response(traceId, route, null, reply, new AiConverseResponse.Result(
            null, new AiConverseResponse.QaCardDraft(question), List.of(), List.of()));
    }

    private static AiConverseResponse response(String traceId, AiRoute route, AiComplaintState nextComplaintState,
                                               String reply, AiConverseResponse.Result result) {
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, traceId,
            new AiConverseResponse.Data(route, nextComplaintState, reply, result));
    }

    private long startConversation(String token) throws Exception {
        String created = mvc.perform(post(CONVERSATIONS).header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"content\":\"천장에서 물이 새요\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(created).path("data").path("conversationId").asLong();
    }

    private ResultActions startConversationWith(String token, long attachmentId)
        throws Exception {
        return mvc.perform(post(CONVERSATIONS).header("Authorization", "Bearer " + token)
            .contentType("application/json")
            .content("{\"content\":\"천장에서 물이 새요\",\"attachmentIds\":[%d]}".formatted(attachmentId)));
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
