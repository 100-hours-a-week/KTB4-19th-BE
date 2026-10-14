package com.homes.zipsai.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.List;
import java.util.Set;
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
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.homes.zipsai.common.service.SseService;
import com.homes.zipsai.conversation.ai.AiComplaintState;
import com.homes.zipsai.conversation.ai.AiConverseClient;
import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.conversation.dto.response.ConversationReplyDeltaResponse;
import com.homes.zipsai.conversation.dto.response.ConversationReplyFailedResponse;
import com.homes.zipsai.conversation.dto.response.ConversationReplyResponse;
import com.homes.zipsai.conversation.dto.response.MessageResponse;
import com.homes.zipsai.global.exception.AiUnavailableException;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.logging.StructuredLogger;

@ExtendWith(MockitoExtension.class)
class ConversationReplyServiceTest {

    private static final long RESIDENT_ID = 1L;
    private static final long CONVERSATION_ID = 10L;
    private static final String TURN_ID = "turn-1";

    @Mock
    ConversationService conversationService;

    @Mock
    AiConverseClient aiConverseClient;

    @Mock
    SseService sseService;

    ConversationReplyService conversationReplyService;

    @BeforeEach
    void setUp() {
        conversationReplyService = new ConversationReplyService(conversationService, aiConverseClient, sseService);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("AI가 답하면 답변을 저장하고 chat-done 이벤트로 보낸다")
    void savesReplyAndSendsDoneEvent() {
        PendingAiReply pendingAiReply = pendingAiReply();
        AiConverseResponse aiResponse = aiResponse(TURN_ID);
        ConversationReplyResponse reply = new ConversationReplyResponse(CONVERSATION_ID, assistantMessage(), null);
        given(aiConverseClient.converse(eq(pendingAiReply.aiRequest()), any())).willReturn(aiResponse);
        given(conversationService.saveAiReply(pendingAiReply, aiResponse)).willReturn(reply);

        conversationReplyService.reply(RESIDENT_ID, pendingAiReply);

        Set<DataWithMediaType> event = sentEvent();
        assertThat(eventName(event)).isEqualTo(ConversationReplyService.REPLY_EVENT);
        assertThat(eventData(event)).isEqualTo(reply);
        then(conversationService).should(never()).discardUnansweredMessage(any());
    }

    @Test
    @DisplayName("AI가 보낸 답변 조각을 순서 번호와 함께 chat-delta 이벤트로 보낸 뒤 chat-done을 보낸다")
    void sendsReplyDeltasInOrderBeforeDoneEvent() {
        PendingAiReply pendingAiReply = pendingAiReply();
        AiConverseResponse aiResponse = aiResponse(TURN_ID);
        given(aiConverseClient.converse(eq(pendingAiReply.aiRequest()), any())).willAnswer(invocation -> {
            Consumer<String> onReplyDelta = invocation.getArgument(1);
            onReplyDelta.accept("위치");
            onReplyDelta.accept("가 ");
            return aiResponse;
        });
        given(conversationService.saveAiReply(pendingAiReply, aiResponse))
            .willReturn(new ConversationReplyResponse(CONVERSATION_ID, assistantMessage(), null));

        conversationReplyService.reply(RESIDENT_ID, pendingAiReply);

        List<Set<DataWithMediaType>> events = sentEvents(3);
        assertThat(events).extracting(ConversationReplyServiceTest::eventName).containsExactly(
            ConversationReplyService.REPLY_DELTA_EVENT, ConversationReplyService.REPLY_DELTA_EVENT,
            ConversationReplyService.REPLY_EVENT);
        assertThat(events.subList(0, 2)).extracting(ConversationReplyServiceTest::eventData).containsExactly(
            new ConversationReplyDeltaResponse(CONVERSATION_ID, 1, "위치"),
            new ConversationReplyDeltaResponse(CONVERSATION_ID, 2, "가 "));
    }

    @Test
    @DisplayName("AI 호출이 실패하면 답변받지 못한 메시지를 지우고 오류 코드를 chat-error 이벤트로 보낸다")
    void discardsUnansweredMessageAndSendsErrorEventWhenAiFails() {
        PendingAiReply pendingAiReply = pendingAiReply();
        given(aiConverseClient.converse(eq(pendingAiReply.aiRequest()), any())).willThrow(new AiUnavailableException());

        conversationReplyService.reply(RESIDENT_ID, pendingAiReply);

        then(conversationService).should().discardUnansweredMessage(pendingAiReply);
        then(conversationService).should(never()).saveAiReply(any(), any());
        Set<DataWithMediaType> event = sentEvent();
        assertThat(eventName(event)).isEqualTo(ConversationReplyService.REPLY_FAILED_EVENT);
        assertThat(eventData(event)).isInstanceOf(ConversationReplyFailedResponse.class)
            .hasFieldOrPropertyWithValue("conversationId", CONVERSATION_ID)
            .hasFieldOrPropertyWithValue("code", new AiUnavailableException().code);
    }

    @Test
    @DisplayName("AI 답변 처리가 실패하면 작업 로그에 남길 오류 코드를 기록한다")
    void recordsErrorCodeForTaskLogWhenReplyFails() {
        PendingAiReply pendingAiReply = pendingAiReply();
        given(aiConverseClient.converse(eq(pendingAiReply.aiRequest()), any())).willThrow(new AiUnavailableException());

        conversationReplyService.reply(RESIDENT_ID, pendingAiReply);

        assertThat(MDC.get(StructuredLogger.ERROR_CODE)).isEqualTo(new AiUnavailableException().code);
    }

    @Test
    @DisplayName("요청과 다른 turn ID로 온 AI 응답은 저장하지 않고 서버 오류로 알린다")
    void rejectsReplyWithAnotherTurnId() {
        PendingAiReply pendingAiReply = pendingAiReply();
        given(aiConverseClient.converse(eq(pendingAiReply.aiRequest()), any())).willReturn(aiResponse("other-turn"));

        conversationReplyService.reply(RESIDENT_ID, pendingAiReply);

        then(conversationService).should().discardUnansweredMessage(pendingAiReply);
        then(conversationService).should(never()).saveAiReply(any(), any());
        assertThat(eventData(sentEvent())).hasFieldOrPropertyWithValue("code", "INTERNAL_SERVER_ERROR");
    }

    @Test
    @DisplayName("예상하지 못한 예외도 메시지를 지우고 서버 오류로 알린다")
    void sendsInternalServerErrorForUnexpectedFailure() {
        PendingAiReply pendingAiReply = pendingAiReply();
        given(aiConverseClient.converse(eq(pendingAiReply.aiRequest()), any()))
            .willThrow(new IllegalStateException("AI timeout"));

        conversationReplyService.reply(RESIDENT_ID, pendingAiReply);

        then(conversationService).should().discardUnansweredMessage(pendingAiReply);
        Set<DataWithMediaType> event = sentEvent();
        assertThat(eventName(event)).isEqualTo(ConversationReplyService.REPLY_FAILED_EVENT);
        assertThat(eventData(event)).hasFieldOrPropertyWithValue("code", "INTERNAL_SERVER_ERROR");
    }

    @Test
    @DisplayName("AI 답을 기다리는 대화는 다시 대기 상태로 만들 수 없다")
    void rejectsStartWhileReplying() {
        conversationReplyService.startReplying(CONVERSATION_ID);

        assertThatThrownBy(() -> conversationReplyService.startReplying(CONVERSATION_ID))
            .isInstanceOf(ConflictException.class)
            .hasFieldOrPropertyWithValue("code", "CONVERSATION_BUSY");
    }

    @Test
    @DisplayName("AI 답변이 끝나면 같은 대화에 다시 보낼 수 있다")
    void allowsNextMessageAfterReply() {
        PendingAiReply pendingAiReply = pendingAiReply();
        given(aiConverseClient.converse(eq(pendingAiReply.aiRequest()), any())).willThrow(new AiUnavailableException());
        conversationReplyService.startReplying(CONVERSATION_ID);
        conversationReplyService.reply(RESIDENT_ID, pendingAiReply);

        assertThatNoException().isThrownBy(() -> conversationReplyService.startReplying(CONVERSATION_ID));
    }

    private Set<DataWithMediaType> sentEvent() {
        ArgumentCaptor<SseEmitter.SseEventBuilder> eventCaptor =
            ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        then(sseService).should().send(eq(RESIDENT_ID), eventCaptor.capture());
        return eventCaptor.getValue().build();
    }

    private List<Set<DataWithMediaType>> sentEvents(int count) {
        ArgumentCaptor<SseEmitter.SseEventBuilder> eventCaptor =
            ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        then(sseService).should(times(count)).send(eq(RESIDENT_ID), eventCaptor.capture());
        return eventCaptor.getAllValues().stream()
            .map(SseEmitter.SseEventBuilder::build)
            .toList();
    }

    private static String eventName(Set<DataWithMediaType> event) {
        return event.iterator().next().getData().toString().lines()
            .filter(line -> line.startsWith("event:"))
            .map(line -> line.substring("event:".length()))
            .findFirst()
            .orElseThrow();
    }

    private static Object eventData(Set<DataWithMediaType> event) {
        return event.stream()
            .filter(part -> MediaType.APPLICATION_JSON.equals(part.getMediaType()))
            .map(DataWithMediaType::getData)
            .findFirst()
            .orElseThrow();
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
        return new PendingAiReply(conversation, residentMessage, false, null, aiRequest);
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
