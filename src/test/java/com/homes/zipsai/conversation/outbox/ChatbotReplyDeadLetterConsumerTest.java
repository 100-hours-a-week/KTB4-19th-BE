package com.homes.zipsai.conversation.outbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.conversation.repository.ChatbotReplyOutboxRepository;
import com.homes.zipsai.conversation.service.ConversationReplyService;

@ExtendWith(MockitoExtension.class)
class ChatbotReplyDeadLetterConsumerTest {

    private static final long EVENT_ID = 900L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 11, 4, 10, 0);

    @Mock
    ChatbotReplyOutboxRepository outboxRepository;

    @Mock
    ConversationReplyService replyService;

    ChatbotReplyDeadLetterConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ChatbotReplyDeadLetterConsumer(outboxRepository, replyService);
    }

    @Test
    @DisplayName("처리 중인 요청은 메시지의 토큰이 아니라 현재 선점 토큰으로 실패 처리한다")
    void failsProcessingRequestWithCurrentLockToken() {
        ChatbotReplyOutbox outbox = givenStoredOutbox();
        outbox.claim("current-token", CREATED_AT.plusSeconds(1));

        consumer.consume(deadLetter());

        then(replyService).should().failReply(EVENT_ID, 0, "current-token");
    }

    @Test
    @DisplayName("아직 선점되지 않은 요청도 실패 처리한다")
    void failsPendingRequest() {
        givenStoredOutbox();

        consumer.consume(deadLetter());

        then(replyService).should().failReply(EVENT_ID, 0, "first-token");
    }

    @Test
    @DisplayName("이미 답변을 저장한 요청은 실패 처리하지 않는다")
    void ignoresCompletedRequest() {
        givenStoredOutbox().complete();

        consumer.consume(deadLetter());

        then(replyService).should(never()).failReply(any(), anyInt(), any());
    }

    @Test
    @DisplayName("이미 실패 처리한 요청은 다시 실패 처리하지 않는다")
    void ignoresFailedRequest() {
        givenStoredOutbox().fail();

        consumer.consume(deadLetter());

        then(replyService).should(never()).failReply(any(), anyInt(), any());
    }

    @Test
    @DisplayName("요청 이벤트가 없으면 아무것도 하지 않는다")
    void ignoresMissingRequest() {
        given(outboxRepository.findById(EVENT_ID)).willReturn(Optional.empty());

        consumer.consume(deadLetter());

        then(replyService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("실패 처리 중 오류가 나면 예외를 던져 DLQ에 다시 넣는다")
    void rethrowsWhenFailReplyFails() {
        givenStoredOutbox();
        willThrow(new QueryTimeoutException("timeout")).given(replyService).failReply(EVENT_ID, 0, "first-token");

        assertThatThrownBy(() -> consumer.consume(deadLetter())).isInstanceOf(QueryTimeoutException.class);
    }

    private ChatbotReplyOutbox givenStoredOutbox() {
        com.homes.zipsai.conversation.domain.Message residentMessage =
            new com.homes.zipsai.conversation.domain.Message(null, "보일러가 안 켜져요", SenderType.RESIDENT,
                MessageType.TEXT, "turn-1");
        ReflectionTestUtils.setField(residentMessage, "id", 500L);
        ChatbotReplyOutbox outbox = ChatbotReplyOutbox.startFirstMessage(residentMessage, 7L, "trace-1",
            "first-token", null, CREATED_AT);
        ReflectionTestUtils.setField(outbox, "id", EVENT_ID);
        given(outboxRepository.findById(EVENT_ID)).willReturn(Optional.of(outbox));
        return outbox;
    }

    private static Message deadLetter() {
        return MessageBuilder.withBody("{\"eventId\":900}".getBytes()).setMessageId(String.valueOf(EVENT_ID)).build();
    }
}
