package com.homes.zipsai.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.homes.zipsai.common.event.RedisMessage;
import com.homes.zipsai.common.service.RedisMessageSubscriber;
import com.homes.zipsai.common.service.SseService;
import com.homes.zipsai.conversation.config.ChatbotReplyPubSubProperties;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.conversation.dto.response.ConversationReplyDeltaResponse;
import com.homes.zipsai.conversation.dto.response.ConversationReplyFailedResponse;
import com.homes.zipsai.conversation.dto.response.ConversationReplyResponse;
import com.homes.zipsai.conversation.dto.response.MessageResponse;
import com.homes.zipsai.conversation.event.ChatbotReplyEvent;
import com.homes.zipsai.global.logging.StructuredLogger;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ChatbotReplyEventSubscriberTest {

    private static final long RESIDENT_ID = 1L;
    private static final long CONVERSATION_ID = 10L;
    private static final String CHANNEL = "test:chatbot-reply-events";

    @Mock
    SseService sseService;

    ObjectMapper objectMapper = new ObjectMapper();

    ChatbotReplyEventSubscriber chatbotReplyEventSubscriber;

    @BeforeEach
    void setUp() {
        chatbotReplyEventSubscriber = new ChatbotReplyEventSubscriber(
            new RedisMessageSubscriber(objectMapper, new StructuredLogger(objectMapper)), sseService,
            new ChatbotReplyPubSubProperties(CHANNEL));
    }

    @ParameterizedTest
    @MethodSource("events")
    @DisplayName("Redis로 받은 챗봇 답변 이벤트를 해당 사용자 SSE에 같은 이벤트 이름과 데이터로 보낸다")
    void sendsReceivedEventToUserSse(ChatbotReplyEvent event, String eventName, Object data) {
        byte[] body = objectMapper.writeValueAsBytes(new RedisMessage<>("trace-id", event));

        chatbotReplyEventSubscriber.onMessage(new DefaultMessage(CHANNEL.getBytes(), body), null);

        ArgumentCaptor<SseEmitter.SseEventBuilder> eventCaptor =
            ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        then(sseService).should().send(eq(RESIDENT_ID), eventCaptor.capture());
        Set<DataWithMediaType> sent = eventCaptor.getValue().build();
        assertThat(sent.iterator().next().getData().toString()).contains("event:" + eventName);
        assertThat(sent).filteredOn(part -> MediaType.APPLICATION_JSON.equals(part.getMediaType()))
            .extracting(DataWithMediaType::getData)
            .containsExactly(data);
    }

    static Stream<Arguments> events() {
        ConversationReplyDeltaResponse delta = new ConversationReplyDeltaResponse(CONVERSATION_ID, 1, "위치");
        ConversationReplyResponse reply = new ConversationReplyResponse(CONVERSATION_ID,
            new MessageResponse(21L, SenderType.ASSISTANT, MessageType.TEXT, "위치가 어디인가요?", List.of(), null,
                LocalDateTime.of(2026, 10, 15, 20, 0)),
            OffsetDateTime.of(2026, 10, 15, 11, 5, 0, 0, ZoneOffset.UTC));
        ConversationReplyFailedResponse failure =
            new ConversationReplyFailedResponse(CONVERSATION_ID, "AI_UNAVAILABLE", "잠시 후 다시 시도해 주세요.", Map.of());
        return Stream.of(
            Arguments.of(ChatbotReplyEvent.of(RESIDENT_ID, delta), "chat-delta", delta),
            Arguments.of(ChatbotReplyEvent.of(RESIDENT_ID, reply), "chat-done", reply),
            Arguments.of(ChatbotReplyEvent.of(RESIDENT_ID, failure), "chat-error",
                failure));
    }
}
