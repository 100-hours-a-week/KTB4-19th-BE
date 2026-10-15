package com.homes.zipsai.conversation.event;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.homes.zipsai.conversation.dto.response.ConversationReplyDeltaResponse;
import com.homes.zipsai.conversation.dto.response.ConversationReplyFailedResponse;
import com.homes.zipsai.conversation.dto.response.ConversationReplyResponse;
import com.homes.zipsai.global.exception.InvalidPubSubFieldException;
import com.homes.zipsai.global.exception.InvalidPubSubFieldException.Reason;

public record ChatbotReplyEvent(
    Type eventType,
    long userId,
    ConversationReplyDeltaResponse delta,
    ConversationReplyResponse reply,
    ConversationReplyFailedResponse failure
) {

    public enum Type {
        DELTA("chat-delta", "delta"),
        DONE("chat-done", "reply"),
        FAILED("chat-error", "failure");

        public final String eventName;
        private final String dataField;

        Type(String eventName, String dataField) {
            this.eventName = eventName;
            this.dataField = dataField;
        }
    }

    public ChatbotReplyEvent {
        if (eventType == null) {
            throw new InvalidPubSubFieldException("eventType", Reason.REQUIRED_VALUE);
        }
        if (userId <= 0) {
            throw new InvalidPubSubFieldException("userId", Reason.INVALID_ID);
        }
        boolean hasData = switch (eventType) {
            case DELTA -> delta != null;
            case DONE -> reply != null;
            case FAILED -> failure != null;
        };
        if (!hasData) {
            throw new InvalidPubSubFieldException(eventType.dataField, Reason.REQUIRED_VALUE);
        }
    }

    public static ChatbotReplyEvent of(long userId, ConversationReplyDeltaResponse delta) {
        return new ChatbotReplyEvent(Type.DELTA, userId, delta, null, null);
    }

    public static ChatbotReplyEvent of(long userId, ConversationReplyResponse reply) {
        return new ChatbotReplyEvent(Type.DONE, userId, null, reply, null);
    }

    public static ChatbotReplyEvent of(long userId, ConversationReplyFailedResponse failure) {
        return new ChatbotReplyEvent(Type.FAILED, userId, null, null, failure);
    }

    public SseEmitter.SseEventBuilder toSseEvent() {
        SseEmitter.SseEventBuilder event = SseEmitter.event().name(eventType.eventName);
        return switch (eventType) {
            case DELTA -> event.data(delta, MediaType.APPLICATION_JSON);
            case DONE -> event.data(reply, MediaType.APPLICATION_JSON);
            case FAILED -> event.data(failure, MediaType.APPLICATION_JSON);
        };
    }
}
