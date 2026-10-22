package com.homes.zipsai.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.ai.AiComplaintState;
import com.homes.zipsai.conversation.ai.AiConverseClient;
import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.repository.ChatbotReplyOutboxRepository;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.conversation.repository.MessageRepository;
import com.homes.zipsai.conversation.service.ConversationReplyLock;
import com.homes.zipsai.global.config.AsyncConfig;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ConversationTestFixture.class)
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
class ConversationMockAiTest {

    private static final String CONVERSATIONS = "/api/v1/residents/me/conversations";
    private static final String RESIDENT_COMPLAINTS = "/api/v1/residents/me/complaints";
    private static final String MANAGER_COMPLAINTS = "/api/v1/managers/me/complaints";

    @Autowired
    MockMvcTester mockMvcTester;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ConversationTestFixture conversationTestFixture;

    @Autowired
    ConversationRepository conversationRepository;

    @Autowired
    MessageRepository messageRepository;

    @Autowired
    ChatbotReplyOutboxRepository chatbotReplyOutboxRepository;

    @MockitoBean
    AiConverseClient aiConverseClient;

    @MockitoBean
    S3StorageService s3StorageService;

    @MockitoBean
    ConversationReplyLock conversationReplyLock;

    @TestBean(name = AsyncConfig.CHATBOT_REPLY_EXECUTOR)
    TaskExecutor chatbotReplyExecutor;

    static TaskExecutor chatbotReplyExecutor() {
        return new SyncTaskExecutor();
    }

    @BeforeEach
    void setUp() {
        given(s3StorageService.prepareDownload(anyString(), any()))
            .willAnswer(invocation ->
                new S3StorageService.PresignedDownload("https://s3.test/" + invocation.getArgument(0)));
        given(conversationReplyLock.acquire(any())).willReturn("lock-token");
    }

    @Test
    @DisplayName("사진을 첨부한 메시지가 저장되고 AI에 사진 URL이 전달된다")
    void savesAttachedImagesAndSendsUrlsToAi() throws Exception {
        String email = conversationTestFixture.livingResident("302");
        RequestPostProcessor resident = conversationTestFixture.authenticatedAs(email);
        long firstImage = conversationTestFixture.uploadedFile(email, "jpg");
        long secondImage = conversationTestFixture.uploadedFile(email, "png");
        given(aiConverseClient.converse(any(), any())).willAnswer(ConversationMockAiTest::collectingReply);

        long conversationId = startConversation(resident, "천장에서 물이 새요", firstImage);

        assertThat(sendMessage(resident, conversationId, "안방이요", secondImage)).hasStatus(HttpStatus.ACCEPTED)
            .bodyJson().extractingPath("$.data.attachments[0].attachmentId").convertTo(Long.class)
            .isEqualTo(secondImage);

        ArgumentCaptor<AiConverseRequest> aiConverseRequestCaptor = ArgumentCaptor.forClass(AiConverseRequest.class);
        then(aiConverseClient).should(times(2)).converse(aiConverseRequestCaptor.capture(), any());
        assertThat(aiConverseRequestCaptor.getAllValues().getFirst().message().images())
            .extracting(image -> image.attachmentId()).containsExactly(firstImage);
        AiConverseRequest followUp = aiConverseRequestCaptor.getAllValues().getLast();
        assertThat(followUp.message().images()).extracting(image -> image.attachmentId()).containsExactly(secondImage);
        assertThat(followUp.conversationHistory().getFirst().images())
            .extracting(image -> image.attachmentId()).containsExactly(firstImage);
        assertThat(followUp.conversationHistory().getLast().images()).isNull();

        MvcTestResult result = getMessages(resident, conversationId);
        assertThat(result).hasStatusOk()
            .bodyJson().isLenientlyEqualTo("""
                {"data": {"messages": [{"senderType": "RESIDENT", "attachments": [{"attachmentId": %d, "seq": 1}]},
                                       {"senderType": "ASSISTANT", "attachments": []},
                                       {"senderType": "RESIDENT", "attachments": [{"attachmentId": %d}]},
                                       {"senderType": "ASSISTANT", "attachments": []}]}}
                """.formatted(firstImage, secondImage));
        assertThat(result).bodyJson().extractingPath("$.data.messages[0].attachments[0].fileUrl")
            .asString().startsWith("https://s3.test/");
    }

    @Test
    @DisplayName("AI 호출이 실패하면 답변받지 못한 메시지의 사진 연결도 지운다")
    void removesImageLinksOfUnansweredMessageWhenAiFails() throws Exception {
        String email = conversationTestFixture.livingResident("302");
        RequestPostProcessor resident = conversationTestFixture.authenticatedAs(email);
        long image = conversationTestFixture.uploadedFile(email, "jpg");
        long before = conversationRepository.count();
        given(aiConverseClient.converse(any(), any()))
            .willThrow(new IllegalStateException("AI timeout"))
            .willAnswer(ConversationMockAiTest::collectingReply);

        assertThat(postConversation(resident, "천장에서 물이 새요", image)).hasStatus(HttpStatus.ACCEPTED);
        assertThat(conversationRepository.count()).isEqualTo(before);

        long conversationId = startConversation(resident, "천장에서 물이 새요", image);
        assertThat(conversationRepository.count()).isEqualTo(before + 1);
        assertThat(getMessages(resident, conversationId)).hasStatusOk()
            .bodyJson().extractingPath("$.data.messages[0].attachments[0].attachmentId").convertTo(Long.class)
            .isEqualTo(image);
    }

    @Test
    @DisplayName("AI 요청에 HTTP 요청 trace_id를 담고 AI turn_id는 따로 메시지 쌍에 저장한다")
    void separatesHttpRequestTraceIdFromAiTurnId() throws Exception {
        String email = conversationTestFixture.livingResident("302");
        RequestPostProcessor resident = conversationTestFixture.authenticatedAs(email);
        given(aiConverseClient.converse(any(), any())).willAnswer(ConversationMockAiTest::collectingReply);

        MvcTestResult result = postConversation(resident, "천장에서 물이 새요");
        assertThat(result).hasStatus(HttpStatus.ACCEPTED);
        String traceId = result.getResponse().getHeader("X-Request-Id");
        long conversationId = objectMapper.readTree(result.getResponse().getContentAsString())
            .path("data").path("conversationId").asLong();
        ArgumentCaptor<AiConverseRequest> aiRequestCaptor = ArgumentCaptor.forClass(AiConverseRequest.class);
        then(aiConverseClient).should().converse(aiRequestCaptor.capture(), any());
        AiConverseRequest aiRequest = aiRequestCaptor.getValue();
        List<Message> messages = messageRepository.findAllByConversationId(conversationId);

        assertThat(aiRequest.traceId()).isEqualTo(traceId);
        assertThat(aiRequest.turnId()).isNotEqualTo(traceId);
        assertThat(messages).extracting(Message::getTurnId).containsExactly(aiRequest.turnId(), aiRequest.turnId());
    }

    @Test
    @DisplayName("AI가 매긴 긴급도가 민원으로 옮겨져 관리자 긴급 목록에 보인다")
    void showsAiUrgencyInManagerUrgentList() throws Exception {
        String email = conversationTestFixture.livingResident("302");
        RequestPostProcessor resident = conversationTestFixture.authenticatedAs(email);
        String managerEmail = conversationTestFixture.managerOf(email);
        RequestPostProcessor manager = conversationTestFixture.authenticatedAs(managerEmail);
        given(aiConverseClient.converse(any(), any())).willAnswer(ConversationMockAiTest::urgentSummaryCardReply);
        long conversationId = startConversation(resident, "세탁기 밑으로 물이 새요");

        assertThat(createComplaint(resident, conversationId)).hasStatus(HttpStatus.CREATED);

        MvcTestResult result = mockMvcTester.get().uri(MANAGER_COMPLAINTS).param("urgentOnly", "true")
            .with(manager).exchange();
        assertThat(result).hasStatusOk()
            .bodyJson().isLenientlyEqualTo("""
                {"data": {"complaints": [{"urgency": 80, "isUrgent": true}]}}
                """);
        assertThat(result).bodyJson().doesNotHavePath("$.data.complaints[0].urgencyVersion");
    }

    private static AiConverseResponse urgentSummaryCardReply(InvocationOnMock invocation) {
        AiConverseRequest request = invocation.getArgument(0);
        AiConverseResponse.DraftPatch draft = AiConverseResponse.DraftPatch.builder()
            .issueType("leak")
            .location("A동 3층 욕실")
            .symptom("세탁기 밑으로 물이 샘")
            .urgencyScore(80)
            .isUrgent(true)
            .urgencyVersion("urgency-llm-v1-phase0")
            .build();
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, request.turnId(),
            new AiConverseResponse.Data(AiRoute.COMPLAINT, null, "A동 3층 욕실의 누수 문제로 민원을 접수할까요?",
                AiConverseResponse.Result.builder().complaintDraft(draft).missingFields(List.of()).build()));
    }

    private static AiConverseResponse collectingReply(InvocationOnMock invocation) {
        AiConverseRequest request = invocation.getArgument(0);
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, request.turnId(),
            new AiConverseResponse.Data(AiRoute.COMPLAINT, AiComplaintState.COLLECTING, "위치가 어디인가요?",
                AiConverseResponse.Result.builder().missingFields(List.of("location")).build()));
    }

    @Test
    @DisplayName("새 대화방의 답변 잠금을 잡지 못하면 대화방, 메시지, 답변 이벤트를 모두 남기지 않는다")
    void rollsBackFirstMessageWhenReplyLockCannotBeAcquired() {
        String email = conversationTestFixture.livingResident("302");
        RequestPostProcessor resident = conversationTestFixture.authenticatedAs(email);
        long conversations = conversationRepository.count();
        long messages = messageRepository.count();
        long events = chatbotReplyOutboxRepository.count();
        given(conversationReplyLock.acquire(any())).willThrow(new RedisConnectionFailureException("Redis 연결 실패"));

        MvcTestResult result = postConversation(resident, "천장에서 물이 새요");

        assertThat(result).hasStatus5xxServerError();
        assertThat(conversationRepository.count()).isEqualTo(conversations);
        assertThat(messageRepository.count()).isEqualTo(messages);
        assertThat(chatbotReplyOutboxRepository.count()).isEqualTo(events);
        then(aiConverseClient).shouldHaveNoInteractions();
    }

    private long startConversation(RequestPostProcessor resident, String content, Long... attachmentIds)
        throws Exception {
        MvcTestResult created = postConversation(resident, content, attachmentIds);
        assertThat(created).hasStatus(HttpStatus.ACCEPTED);
        return objectMapper.readTree(created.getResponse().getContentAsString())
            .path("data").path("conversationId").asLong();
    }

    private MvcTestResult postConversation(RequestPostProcessor resident, String content, Long... attachmentIds) {
        return mockMvcTester.post().uri(CONVERSATIONS).with(resident)
            .contentType(MediaType.APPLICATION_JSON).content(messageBody(content, attachmentIds))
            .exchange();
    }

    private MvcTestResult sendMessage(RequestPostProcessor resident, long conversationId, String content,
                                      Long... attachmentIds) {
        return mockMvcTester.post().uri(CONVERSATIONS + "/{id}/messages", conversationId).with(resident)
            .contentType(MediaType.APPLICATION_JSON).content(messageBody(content, attachmentIds))
            .exchange();
    }

    private MvcTestResult createComplaint(RequestPostProcessor resident, long conversationId) {
        return mockMvcTester.post().uri(RESIDENT_COMPLAINTS).with(resident)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("conversationId", conversationId)))
            .exchange();
    }

    private MvcTestResult getMessages(RequestPostProcessor resident, long conversationId) {
        return mockMvcTester.get().uri(CONVERSATIONS + "/{id}/messages", conversationId).with(resident)
            .exchange();
    }

    private String messageBody(String content, Long... attachmentIds) {
        return objectMapper.writeValueAsString(Map.of("content", content, "attachmentIds", List.of(attachmentIds)));
    }
}
