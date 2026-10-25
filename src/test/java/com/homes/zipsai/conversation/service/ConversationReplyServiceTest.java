package com.homes.zipsai.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.common.service.RedisMessagePublisher;
import com.homes.zipsai.conversation.ai.AiComplaintState;
import com.homes.zipsai.conversation.ai.AiConverseClient;
import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.conversation.config.ChatbotReplyPubSubProperties;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.conversation.dto.response.ConversationReplyDeltaResponse;
import com.homes.zipsai.conversation.dto.response.ConversationReplyResponse;
import com.homes.zipsai.conversation.dto.response.MessageResponse;
import com.homes.zipsai.conversation.event.ChatbotReplyEvent;
import com.homes.zipsai.global.exception.AiUnavailableException;
import com.homes.zipsai.global.logging.StructuredLogger;

@ExtendWith(MockitoExtension.class)
class ConversationReplyServiceTest {

    private static final long RESIDENT_ID = 1L;
    private static final long CONVERSATION_ID = 10L;
    private static final long EVENT_ID = 900L;
    private static final String TURN_ID = "turn-1";
    private static final String LOCK_TOKEN = "lock-token";
    private static final String CHANNEL = "test:chatbot-reply-events";

    @Mock
    ConversationService conversationService;

    @Mock
    AiConverseClient aiConverseClient;

    @Mock
    RedisMessagePublisher redisMessagePublisher;

    @Mock
    ConversationReplyLock conversationReplyLock;

    @Mock
    ChatbotReplyShutdownSignal chatbotReplyShutdownSignal;

    @Mock
    StructuredLogger structuredLogger;

    ConversationReplyService conversationReplyService;

    @BeforeEach
    void setUp() {
        conversationReplyService = new ConversationReplyService(conversationService, aiConverseClient,
            redisMessagePublisher, new ChatbotReplyPubSubProperties(CHANNEL), conversationReplyLock,
            chatbotReplyShutdownSignal, structuredLogger);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("AI가 답하면 답변을 저장하고 chat-done 이벤트로 발행한다")
    void savesReplyAndSendsDoneEvent() {
        PendingAiReply pendingAiReply = pendingAiReply();
        AiConverseResponse aiResponse = aiResponse(TURN_ID);
        ConversationReplyResponse reply = new ConversationReplyResponse(CONVERSATION_ID, assistantMessage(), null);
        given(aiConverseClient.converse(eq(pendingAiReply.aiRequest()), any())).willReturn(aiResponse);
        given(conversationService.saveAiReply(pendingAiReply, aiResponse)).willReturn(Optional.of(reply));

        conversationReplyService.reply(pendingAiReply);

        assertThat(publishedEvent()).isEqualTo(ChatbotReplyEvent.of(RESIDENT_ID, reply));
        then(conversationService).should(never()).discardUnansweredMessage(any(), anyInt());
    }

    @Test
    @DisplayName("AI가 보낸 답변 조각을 순서 번호와 함께 chat-delta 이벤트로 발행한 뒤 chat-done을 발행한다")
    void sendsReplyDeltasInOrderBeforeDoneEvent() {
        PendingAiReply pendingAiReply = pendingAiReply();
        AiConverseResponse aiResponse = aiResponse(TURN_ID);
        given(aiConverseClient.converse(eq(pendingAiReply.aiRequest()), any())).willAnswer(invocation -> {
            Consumer<String> onReplyDelta = invocation.getArgument(1);
            onReplyDelta.accept("위치");
            onReplyDelta.accept("가 ");
            return aiResponse;
        });
        ConversationReplyResponse reply = new ConversationReplyResponse(CONVERSATION_ID, assistantMessage(), null);
        given(conversationService.saveAiReply(pendingAiReply, aiResponse)).willReturn(Optional.of(reply));

        conversationReplyService.reply(pendingAiReply);

        assertThat(publishedEvents(3)).containsExactly(
            ChatbotReplyEvent.of(RESIDENT_ID, new ConversationReplyDeltaResponse(CONVERSATION_ID, 1, "위치")),
            ChatbotReplyEvent.of(RESIDENT_ID, new ConversationReplyDeltaResponse(CONVERSATION_ID, 2, "가 ")),
            ChatbotReplyEvent.of(RESIDENT_ID, reply));
    }

    @Test
    @DisplayName("AI 호출이 실패하면 답변받지 못한 메시지를 지우고 오류 코드를 chat-error 이벤트로 발행한다")
    void discardsUnansweredMessageAndSendsErrorEventWhenAiFails() {
        PendingAiReply pendingAiReply = pendingAiReply();
        given(aiConverseClient.converse(eq(pendingAiReply.aiRequest()), any())).willThrow(new AiUnavailableException());
        givenDiscarded();

        conversationReplyService.reply(pendingAiReply);

        then(conversationService).should().discardUnansweredMessage(EVENT_ID, 0);
        then(conversationService).should(never()).saveAiReply(any(), any());
        ChatbotReplyEvent event = publishedEvent();
        assertThat(event.eventType()).isEqualTo(ChatbotReplyEvent.Type.FAILED);
        assertThat(event.failure())
            .hasFieldOrPropertyWithValue("conversationId", CONVERSATION_ID)
            .hasFieldOrPropertyWithValue("code", new AiUnavailableException().code);
    }

    @Test
    @DisplayName("AI 답변 처리가 실패하면 작업 로그에 남길 오류 코드를 기록한다")
    void recordsErrorCodeForTaskLogWhenReplyFails() {
        PendingAiReply pendingAiReply = pendingAiReply();
        given(aiConverseClient.converse(eq(pendingAiReply.aiRequest()), any())).willThrow(new AiUnavailableException());
        givenDiscarded();

        conversationReplyService.reply(pendingAiReply);

        assertThat(MDC.get(StructuredLogger.ERROR_CODE)).isEqualTo(new AiUnavailableException().code);
    }

    @Test
    @DisplayName("요청과 다른 turn ID로 온 AI 응답은 저장하지 않고 서버 오류로 알린다")
    void rejectsReplyWithAnotherTurnId() {
        PendingAiReply pendingAiReply = pendingAiReply();
        given(aiConverseClient.converse(eq(pendingAiReply.aiRequest()), any())).willReturn(aiResponse("other-turn"));
        givenDiscarded();

        conversationReplyService.reply(pendingAiReply);

        then(conversationService).should().discardUnansweredMessage(EVENT_ID, 0);
        then(conversationService).should(never()).saveAiReply(any(), any());
        assertThat(publishedEvent().failure()).hasFieldOrPropertyWithValue("code", "INTERNAL_SERVER_ERROR");
    }

    @Test
    @DisplayName("예상하지 못한 예외도 메시지를 지우고 서버 오류로 알린다")
    void sendsInternalServerErrorForUnexpectedFailure() {
        PendingAiReply pendingAiReply = pendingAiReply();
        given(aiConverseClient.converse(eq(pendingAiReply.aiRequest()), any()))
            .willThrow(new IllegalStateException("AI timeout"));
        givenDiscarded();

        conversationReplyService.reply(pendingAiReply);

        then(conversationService).should().discardUnansweredMessage(EVENT_ID, 0);
        ChatbotReplyEvent event = publishedEvent();
        assertThat(event.eventType()).isEqualTo(ChatbotReplyEvent.Type.FAILED);
        assertThat(event.failure()).hasFieldOrPropertyWithValue("code", "INTERNAL_SERVER_ERROR");
    }

    @Test
    @DisplayName("AI 답변이 실패해도 대화방 답변 잠금을 푼다")
    void releasesLockWhenReplyFails() {
        PendingAiReply pendingAiReply = pendingAiReply();
        given(aiConverseClient.converse(eq(pendingAiReply.aiRequest()), any())).willThrow(new AiUnavailableException());
        givenDiscarded();

        conversationReplyService.reply(pendingAiReply);

        then(conversationReplyLock).should().release(CONVERSATION_ID, LOCK_TOKEN);
    }

    @Test
    @DisplayName("다른 시도가 이미 맡은 이벤트면 답변을 저장해도 잠금을 풀지 않고 알리지 않는다")
    void skipsLockReleaseAndEventWhenAnotherAttemptOwnsReply() {
        PendingAiReply pendingAiReply = pendingAiReply();
        AiConverseResponse aiResponse = aiResponse(TURN_ID);
        given(aiConverseClient.converse(eq(pendingAiReply.aiRequest()), any())).willReturn(aiResponse);
        given(conversationService.saveAiReply(pendingAiReply, aiResponse)).willReturn(Optional.empty());

        conversationReplyService.reply(pendingAiReply);

        then(conversationReplyLock).should(never()).release(any(), any());
        then(redisMessagePublisher).should(never()).publish(any(), any());
    }

    @Test
    @DisplayName("다른 시도가 이미 맡은 이벤트면 AI가 실패해도 잠금을 풀지 않고 알리지 않는다")
    void skipsLockReleaseAndEventWhenAnotherAttemptOwnsFailure() {
        PendingAiReply pendingAiReply = pendingAiReply();
        given(aiConverseClient.converse(eq(pendingAiReply.aiRequest()), any())).willThrow(new AiUnavailableException());
        given(conversationService.discardUnansweredMessage(EVENT_ID, 0)).willReturn(Optional.empty());

        conversationReplyService.reply(pendingAiReply);

        then(conversationReplyLock).should(never()).release(any(), any());
        then(redisMessagePublisher).should(never()).publish(any(), any());
    }

    @Test
    @DisplayName("서버가 종료되는 중에 AI 호출이 끊기면 메시지를 지우지 않고 잠금도 풀지 않는다")
    void keepsMessageAndLockWhenAiIsInterruptedByShutdown() {
        PendingAiReply pendingAiReply = pendingAiReply();
        given(aiConverseClient.converse(eq(pendingAiReply.aiRequest()), any())).willThrow(new AiUnavailableException());
        given(chatbotReplyShutdownSignal.isShuttingDown()).willReturn(true);

        conversationReplyService.reply(pendingAiReply);

        then(conversationService).should(never()).discardUnansweredMessage(any(), anyInt());
        then(conversationReplyLock).should(never()).release(any(), any());
        then(redisMessagePublisher).should(never()).publish(any(), any());
    }

    @Test
    @DisplayName("워커가 답변을 포기하면 메시지를 지운 뒤 잠금을 풀고 서버 오류를 chat-error 이벤트로 발행한다")
    void releasesLockAndSendsErrorEventWhenWorkerGivesUp() {
        given(conversationService.discardUnansweredMessage(EVENT_ID, 2))
            .willReturn(Optional.of(new DiscardedMessage(RESIDENT_ID, CONVERSATION_ID)));

        conversationReplyService.failReply(EVENT_ID, 2, LOCK_TOKEN);

        then(conversationReplyLock).should().release(CONVERSATION_ID, LOCK_TOKEN);
        ChatbotReplyEvent event = publishedEvent();
        assertThat(event.eventType()).isEqualTo(ChatbotReplyEvent.Type.FAILED);
        assertThat(event.userId()).isEqualTo(RESIDENT_ID);
        assertThat(event.failure())
            .hasFieldOrPropertyWithValue("conversationId", CONVERSATION_ID)
            .hasFieldOrPropertyWithValue("code", "INTERNAL_SERVER_ERROR");
    }

    @Test
    @DisplayName("워커가 포기하려는 이벤트를 다른 시도가 맡았으면 잠금을 풀지 않고 알리지 않는다")
    void skipsLockReleaseAndEventWhenAnotherAttemptOwnsGivenUpReply() {
        given(conversationService.discardUnansweredMessage(EVENT_ID, 2)).willReturn(Optional.empty());

        conversationReplyService.failReply(EVENT_ID, 2, LOCK_TOKEN);

        then(conversationReplyLock).should(never()).release(any(), any());
        then(redisMessagePublisher).should(never()).publish(any(), any());
    }

    private void givenDiscarded() {
        given(conversationService.discardUnansweredMessage(EVENT_ID, 0))
            .willReturn(Optional.of(new DiscardedMessage(RESIDENT_ID, CONVERSATION_ID)));
    }

    private ChatbotReplyEvent publishedEvent() {
        ArgumentCaptor<ChatbotReplyEvent> eventCaptor = ArgumentCaptor.forClass(ChatbotReplyEvent.class);
        then(redisMessagePublisher).should().publish(eq(CHANNEL), eventCaptor.capture());
        return eventCaptor.getValue();
    }

    private List<ChatbotReplyEvent> publishedEvents(int count) {
        ArgumentCaptor<ChatbotReplyEvent> eventCaptor = ArgumentCaptor.forClass(ChatbotReplyEvent.class);
        then(redisMessagePublisher).should(times(count)).publish(eq(CHANNEL), eventCaptor.capture());
        return eventCaptor.getAllValues();
    }

    private static PendingAiReply pendingAiReply() {
        Conversation conversation = Conversation.builder()
            .type(ConversationType.INQUIRY)
            .title("천장에서 물이 새요")
            .build();
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        MessageResponse residentMessage =
            new MessageResponse(20L, SenderType.RESIDENT, MessageType.TEXT, "천장에서 물이 새요", List.of(), null, null);
        AiConverseRequest aiRequest =
            new AiConverseRequest(null, null, null, null, TURN_ID, null, null, null, null, List.of(), null);
        return new PendingAiReply(EVENT_ID, 0, LOCK_TOKEN, RESIDENT_ID, conversation, residentMessage, aiRequest);
    }

    private static MessageResponse assistantMessage() {
        return new MessageResponse(21L, SenderType.ASSISTANT, MessageType.TEXT, "위치가 어디인가요?", List.of(), null, null);
    }

    private static AiConverseResponse aiResponse(String turnId) {
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, turnId,
            new AiConverseResponse.Data(AiRoute.COMPLAINT, AiComplaintState.COLLECTING, "위치가 어디인가요?",
                AiConverseResponse.Result.builder().missingFields(List.of("location")).build()));
    }
}
