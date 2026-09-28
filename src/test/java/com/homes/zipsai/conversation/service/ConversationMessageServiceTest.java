package com.homes.zipsai.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.conversation.ai.AiComplaintState;
import com.homes.zipsai.conversation.ai.AiConverseClient;
import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.conversation.dto.response.ConversationCreateResponse;
import com.homes.zipsai.conversation.dto.response.MessageResponse;
import com.homes.zipsai.conversation.dto.response.MessageSendResponse;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.InternalServerException;

@ExtendWith(MockitoExtension.class)
class ConversationMessageServiceTest {

    private static final long RESIDENT_ID = 1L;
    private static final long CONVERSATION_ID = 10L;
    private static final String TRACE_ID = "trace-1";

    @Mock
    ConversationService conversationService;

    @Mock
    AiConverseClient aiConverseClient;

    ConversationMessageService conversationMessageService;

    @BeforeEach
    void setUp() {
        conversationMessageService = new ConversationMessageService(conversationService, aiConverseClient);
    }

    @Test
    @DisplayName("AI가 답하면 답변을 저장하고 대화 ID를 돌려준다")
    void savesReplyAndReturnsConversationId() {
        PendingAiReply pendingAiReply = pendingAiReply(true);
        AiConverseResponse aiResponse = aiResponse(TRACE_ID);
        given(conversationService.saveFirstMessage(RESIDENT_ID, "천장에서 물이 새요", List.of()))
            .willReturn(pendingAiReply);
        given(aiConverseClient.converse(pendingAiReply.aiRequest())).willReturn(aiResponse);

        ConversationCreateResponse response =
            conversationMessageService.createConversation(RESIDENT_ID, "천장에서 물이 새요", List.of());

        assertThat(response.conversationId()).isEqualTo(CONVERSATION_ID);
        then(conversationService).should().saveAiReply(pendingAiReply, aiResponse);
        then(conversationService).should(never()).discardUnansweredMessage(any());
    }

    @Test
    @DisplayName("AI 호출이 실패하면 답변받지 못한 메시지를 지우고 예외를 그대로 던진다")
    void discardsUnansweredMessageWhenAiFails() {
        PendingAiReply pendingAiReply = pendingAiReply(true);
        IllegalStateException timeout = new IllegalStateException("AI timeout");
        given(conversationService.saveFirstMessage(RESIDENT_ID, "천장에서 물이 새요", List.of()))
            .willReturn(pendingAiReply);
        given(aiConverseClient.converse(pendingAiReply.aiRequest())).willThrow(timeout);

        assertThatThrownBy(() -> conversationMessageService.createConversation(RESIDENT_ID, "천장에서 물이 새요", List.of()))
            .isSameAs(timeout);
        then(conversationService).should().discardUnansweredMessage(pendingAiReply);
        then(conversationService).should(never()).saveAiReply(any(), any());
    }

    @Test
    @DisplayName("요청과 다른 추적 ID로 온 AI 응답은 저장하지 않고 메시지를 지운다")
    void rejectsReplyWithAnotherTraceId() {
        PendingAiReply pendingAiReply = pendingAiReply(true);
        given(conversationService.saveFirstMessage(RESIDENT_ID, "분리수거 요일이 언제인가요?", List.of()))
            .willReturn(pendingAiReply);
        given(aiConverseClient.converse(pendingAiReply.aiRequest())).willReturn(aiResponse("other-trace"));

        assertThatThrownBy(() ->
            conversationMessageService.createConversation(RESIDENT_ID, "분리수거 요일이 언제인가요?", List.of()))
            .isInstanceOf(InternalServerException.class)
            .hasFieldOrPropertyWithValue("code", "INTERNAL_SERVER_ERROR");
        then(conversationService).should().discardUnansweredMessage(pendingAiReply);
        then(conversationService).should(never()).saveAiReply(any(), any());
    }

    @Test
    @DisplayName("AI 답을 기다리는 대화에 메시지를 보내면 거절한다")
    void rejectsMessageWhileAiIsResponding() throws Exception {
        PendingAiReply pendingAiReply = pendingAiReply(false);
        CountDownLatch aiEntered = new CountDownLatch(1);
        CountDownLatch releaseAi = new CountDownLatch(1);
        given(conversationService.saveNextMessage(RESIDENT_ID, CONVERSATION_ID, "안방이요", List.of()))
            .willReturn(pendingAiReply);
        given(aiConverseClient.converse(pendingAiReply.aiRequest())).willAnswer(invocation -> {
            aiEntered.countDown();
            releaseAi.await(5, TimeUnit.SECONDS);
            return aiResponse(TRACE_ID);
        });
        CompletableFuture<?> firstSend = CompletableFuture.runAsync(() ->
            conversationMessageService.sendMessage(RESIDENT_ID, CONVERSATION_ID, "안방이요", List.of()));
        assertThat(aiEntered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() ->
            conversationMessageService.sendMessage(RESIDENT_ID, CONVERSATION_ID, "두 번째 메시지", List.of()))
            .isInstanceOf(ConflictException.class)
            .hasFieldOrPropertyWithValue("code", "CONVERSATION_BUSY");

        releaseAi.countDown();
        firstSend.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("AI 호출이 실패해도 같은 대화에 다시 보낼 수 있다")
    void allowsNextMessageAfterAiFailure() {
        PendingAiReply pendingAiReply = pendingAiReply(false);
        AiConverseResponse aiResponse = aiResponse(TRACE_ID);
        given(conversationService.saveNextMessage(RESIDENT_ID, CONVERSATION_ID, "안방이요", List.of()))
            .willReturn(pendingAiReply);
        given(aiConverseClient.converse(pendingAiReply.aiRequest()))
            .willThrow(new IllegalStateException("AI timeout"))
            .willReturn(aiResponse);
        given(conversationService.saveAiReply(pendingAiReply, aiResponse)).willReturn(assistantMessage());
        assertThatThrownBy(() ->
            conversationMessageService.sendMessage(RESIDENT_ID, CONVERSATION_ID, "안방이요", List.of()))
            .isInstanceOf(IllegalStateException.class);

        MessageSendResponse response =
            conversationMessageService.sendMessage(RESIDENT_ID, CONVERSATION_ID, "안방이요", List.of());

        assertThat(response.assistantMessage().messageId()).isEqualTo(21L);
    }

    private static PendingAiReply pendingAiReply(boolean newConversation) {
        Conversation conversation = Conversation.builder()
            .type(ConversationType.INQUIRY)
            .title("천장에서 물이 새요")
            .build();
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        MessageResponse residentMessage =
            new MessageResponse(20L, SenderType.RESIDENT, MessageType.TEXT, "천장에서 물이 새요", List.of(), null, null);
        AiConverseRequest aiRequest =
            new AiConverseRequest(null, null, null, null, TRACE_ID, null, null, null, List.of(), null);
        return new PendingAiReply(conversation, residentMessage, newConversation, null, aiRequest);
    }

    private static MessageResponse assistantMessage() {
        return new MessageResponse(21L, SenderType.ASSISTANT, MessageType.TEXT, "위치가 어디인가요?", List.of(), null, null);
    }

    private static AiConverseResponse aiResponse(String traceId) {
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, traceId,
            new AiConverseResponse.Data(AiRoute.COMPLAINT, AiComplaintState.COLLECTING, "위치가 어디인가요?",
                new AiConverseResponse.Result(null, null, List.of("location"), List.of())));
    }
}
