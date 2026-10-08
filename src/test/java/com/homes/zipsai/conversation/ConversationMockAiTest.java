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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
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
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.conversation.repository.MessageRepository;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ConversationTestFixture.class)
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
class ConversationMockAiTest {

    private static final String CONVERSATIONS = "/api/v1/residents/me/conversations";

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
    @DisplayName("사진을 첨부한 메시지가 저장되고 AI에 사진 URL이 전달된다")
    void savesAttachedImagesAndSendsUrlsToAi() throws Exception {
        String email = conversationTestFixture.livingResident("302");
        RequestPostProcessor resident = conversationTestFixture.authenticatedAs(email);
        long firstImage = conversationTestFixture.uploadedFile(email, "jpg");
        long secondImage = conversationTestFixture.uploadedFile(email, "png");
        given(aiConverseClient.converse(any())).willAnswer(ConversationMockAiTest::collectingReply);

        long conversationId = startConversation(resident, "천장에서 물이 새요", firstImage);

        assertThat(sendMessage(resident, conversationId, "안방이요", secondImage)).hasStatus(HttpStatus.CREATED)
            .bodyJson().extractingPath("$.data.attachments[0].attachmentId").convertTo(Long.class)
            .isEqualTo(secondImage);

        ArgumentCaptor<AiConverseRequest> aiConverseRequestCaptor = ArgumentCaptor.forClass(AiConverseRequest.class);
        then(aiConverseClient).should(times(2)).converse(aiConverseRequestCaptor.capture());
        assertThat(aiConverseRequestCaptor.getAllValues().getFirst().message().imageUrls()).hasSize(1);
        AiConverseRequest followUp = aiConverseRequestCaptor.getAllValues().getLast();
        assertThat(followUp.message().imageUrls()).hasSize(1);
        assertThat(followUp.conversationHistory().getFirst().imageUrls()).hasSize(1);
        assertThat(followUp.conversationHistory().getLast().imageUrls()).isEmpty();

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
        given(aiConverseClient.converse(any()))
            .willThrow(new IllegalStateException("AI timeout"))
            .willAnswer(ConversationMockAiTest::collectingReply);

        assertThat(postConversation(resident, "천장에서 물이 새요", image))
            .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
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
        given(aiConverseClient.converse(any())).willAnswer(ConversationMockAiTest::collectingReply);

        MvcTestResult result = postConversation(resident, "천장에서 물이 새요");
        assertThat(result).hasStatus(HttpStatus.CREATED);
        String traceId = result.getResponse().getHeader("X-Request-Id");
        long conversationId = objectMapper.readTree(result.getResponse().getContentAsString())
            .path("data").path("conversationId").asLong();
        ArgumentCaptor<AiConverseRequest> aiRequestCaptor = ArgumentCaptor.forClass(AiConverseRequest.class);
        then(aiConverseClient).should().converse(aiRequestCaptor.capture());
        AiConverseRequest aiRequest = aiRequestCaptor.getValue();
        List<Message> messages = messageRepository.findAllByConversationId(conversationId);

        assertThat(aiRequest.traceId()).isEqualTo(traceId);
        assertThat(aiRequest.turnId()).isNotEqualTo(traceId);
        assertThat(messages).extracting(Message::getTurnId).containsExactly(aiRequest.turnId(), aiRequest.turnId());
    }

    private static AiConverseResponse collectingReply(InvocationOnMock invocation) {
        AiConverseRequest request = invocation.getArgument(0);
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, request.turnId(),
            new AiConverseResponse.Data(AiRoute.COMPLAINT, AiComplaintState.COLLECTING, "위치가 어디인가요?",
                AiConverseResponse.Result.builder().missingFields(List.of("location")).build()));
    }

    private long startConversation(RequestPostProcessor resident, String content, Long... attachmentIds)
        throws Exception {
        MvcTestResult created = postConversation(resident, content, attachmentIds);
        assertThat(created).hasStatus(HttpStatus.CREATED);
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

    private MvcTestResult getMessages(RequestPostProcessor resident, long conversationId) {
        return mockMvcTester.get().uri(CONVERSATIONS + "/{id}/messages", conversationId).with(resident)
            .exchange();
    }

    private String messageBody(String content, Long... attachmentIds) {
        return objectMapper.writeValueAsString(Map.of("content", content, "attachmentIds", List.of(attachmentIds)));
    }
}
