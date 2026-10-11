package com.homes.zipsai.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.homes.zipsai.common.service.SseService;
import com.homes.zipsai.conversation.ai.AiComplaintState;
import com.homes.zipsai.conversation.ai.AiConverseClient;
import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.ai.AiRoute;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ConversationTestFixture.class)
class ConversationAsyncReplyTest {

    private static final String CONVERSATIONS = "/api/v1/residents/me/conversations";

    @Autowired
    MockMvcTester mockMvcTester;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ConversationTestFixture conversationTestFixture;

    @MockitoBean
    AiConverseClient aiConverseClient;

    @MockitoSpyBean
    SseService sseService;

    @Test
    @DisplayName("AI 답변은 요청 스레드가 아닌 챗봇 가상 스레드에서 요청의 traceId를 이어받아 처리한다")
    void repliesOnChatbotVirtualThreadWithRequestTraceId() throws Exception {
        RequestPostProcessor resident =
            conversationTestFixture.authenticatedAs(conversationTestFixture.livingResident("302"));
        CompletableFuture<Thread> aiThread = new CompletableFuture<>();
        CompletableFuture<String> aiTraceId = new CompletableFuture<>();
        given(aiConverseClient.converse(any())).willAnswer(invocation -> {
            aiThread.complete(Thread.currentThread());
            aiTraceId.complete(MDC.get("traceId"));
            return collectingReply(invocation);
        });

        MvcTestResult result = mockMvcTester.post().uri(CONVERSATIONS).with(resident)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("content", "천장에서 물이 새요")))
            .exchange();

        assertThat(result).hasStatus(HttpStatus.ACCEPTED);
        Thread thread = aiThread.get(5, TimeUnit.SECONDS);
        assertThat(thread.isVirtual()).isTrue();
        assertThat(thread.getName()).startsWith("chatbot-reply-");
        assertThat(aiTraceId.get(5, TimeUnit.SECONDS)).isEqualTo(result.getResponse().getHeader("X-Request-Id"));
        then(sseService).should(timeout(5000)).send(anyLong(), any());
    }

    private static AiConverseResponse collectingReply(InvocationOnMock invocation) {
        AiConverseRequest request = invocation.getArgument(0);
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, request.turnId(),
            new AiConverseResponse.Data(AiRoute.COMPLAINT, AiComplaintState.COLLECTING, "위치가 어디인가요?",
                AiConverseResponse.Result.builder().missingFields(List.of("location")).build()));
    }
}
