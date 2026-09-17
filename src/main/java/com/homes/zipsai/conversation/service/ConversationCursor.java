package com.homes.zipsai.conversation.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.global.exception.InvalidQueryParameterException;

record ConversationCursor(LocalDateTime lastMessageAt, Long conversationId) {

    private static final String SEPARATOR = "_";

    static ConversationCursor from(Conversation conversation) {
        return new ConversationCursor(conversation.getLastMessageAt(), conversation.getId());
    }

    static ConversationCursor parse(String cursor) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split(SEPARATOR);
            if (parts.length != 2) {
                throw invalidCursor();
            }
            return new ConversationCursor(LocalDateTime.parse(parts[0]), Long.parseLong(parts[1]));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw invalidCursor();
        }
    }

    String encode() {
        String value = lastMessageAt + SEPARATOR + conversationId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static InvalidQueryParameterException invalidCursor() {
        return InvalidQueryParameterException.typeMismatch("cursor", String.class);
    }
}
