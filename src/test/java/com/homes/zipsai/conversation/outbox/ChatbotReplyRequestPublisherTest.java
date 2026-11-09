package com.homes.zipsai.conversation.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.service.PendingAiReply;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ChatbotReplyRequestPublisherTest {

    private static final long EVENT_ID = 900L;
    private static final String EXCHANGE = "chatbot.reply";
    private static final String ROUTING_KEY = "chatbot.reply.request";

    @Mock
    RabbitTemplate rabbitTemplate;

    ObjectMapper objectMapper = JsonMapper.builder().build();
    ChatbotReplyRequestPublisher publisher;
    PendingAiReply pendingReply;

    @BeforeEach
    void setUp() {
        publisher = new ChatbotReplyRequestPublisher(rabbitTemplate, new ChatbotReplyRelayProperties(EXCHANGE,
            ROUTING_KEY, Duration.ofSeconds(10), Duration.ofMillis(50), 50), objectMapper);
        AiConverseRequest aiRequest = new AiConverseRequest(1L, "302", "7", "42", "turn-1", "trace-1", null, null,
            new AiConverseRequest.MessagePayload("500", "보일러가 안 켜져요", List.of()), List.of(), null);
        pendingReply = new PendingAiReply(EVENT_ID, 0, "lock-token", 7L, null, null, aiRequest);
    }

    @Test
    @DisplayName("이벤트 ID와 AI 요청 전체를 본문에 담아 발행한다")
    void publishesEventIdWithWholeRequest() {
        givenBrokerConfirms(true);

        publisher.publish(pendingReply);

        JsonNode body = objectMapper.readTree(sentMessage().getBody());

        assertThat(body.get("eventId").asLong()).isEqualTo(EVENT_ID);

        assertThat(body.get("request"))
            .isEqualTo(objectMapper.readTree(objectMapper.writeValueAsString(pendingReply.aiRequest())));
    }

    @Test
    @DisplayName("발행 메시지는 JSON이고 이벤트 ID를 메시지 ID로 쓴다")
    void publishesJsonMessageWithEventIdAsMessageId() {
        givenBrokerConfirms(true);

        publisher.publish(pendingReply);

        MessageProperties properties = sentMessage().getMessageProperties();

        assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);

        assertThat(properties.getMessageId()).isEqualTo(String.valueOf(EVENT_ID));
    }

    @Test
    @DisplayName("브로커가 확인하면 발행 성공으로 본다")
    void succeedsWhenBrokerConfirms() {
        givenBrokerConfirms(true);

        assertThat(publisher.publish(pendingReply)).isTrue();
    }

    @Test
    @DisplayName("브로커가 거절하면 발행 실패로 본다")
    void failsWhenBrokerRejects() {
        givenBrokerConfirms(false);

        assertThat(publisher.publish(pendingReply)).isFalse();
    }

    @Test
    @DisplayName("큐로 라우팅되지 않고 돌아오면 발행 실패로 본다")
    void failsWhenMessageIsReturned() {
        willAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(invocation.getArgument(2), 312, "NO_ROUTE", EXCHANGE,
                ROUTING_KEY));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).given(rabbitTemplate).send(eq(EXCHANGE), eq(ROUTING_KEY), any(Message.class), any(CorrelationData.class));

        assertThat(publisher.publish(pendingReply)).isFalse();
    }

    @Test
    @DisplayName("확인 대기 시간이 지나면 발행 실패로 본다")
    void failsWhenConfirmTimesOut() {
        assertThat(publisher.publish(pendingReply)).isFalse();
    }

    @Test
    @DisplayName("브로커에 연결하지 못하면 발행 실패로 본다")
    void failsWhenBrokerIsUnreachable() {
        willThrow(new AmqpConnectException(new RuntimeException("connection refused"))).given(rabbitTemplate)
            .send(eq(EXCHANGE), eq(ROUTING_KEY), any(Message.class), any(CorrelationData.class));

        assertThat(publisher.publish(pendingReply)).isFalse();
    }

    private void givenBrokerConfirms(boolean ack) {
        willAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, ack ? null : "rejected"));
            return null;
        }).given(rabbitTemplate).send(eq(EXCHANGE), eq(ROUTING_KEY), any(Message.class), any(CorrelationData.class));
    }

    private Message sentMessage() {
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        then(rabbitTemplate).should()
            .send(eq(EXCHANGE), eq(ROUTING_KEY), messageCaptor.capture(), any(CorrelationData.class));

        return messageCaptor.getValue();
    }
}
