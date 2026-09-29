package com.homes.zipsai.conversation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;

class ConversationListItemResponseTest {

    private static final LocalDateTime NOON = LocalDateTime.of(2026, 9, 29, 12, 0);

    @Test
    @DisplayName("마지막 메시지 후 5분이 지난 대화는 목록에 대화 종료로 내려준다")
    void showsIdleConversationAsClosed() {
        Conversation conversation = Conversation.builder()
            .type(ConversationType.INQUIRY)
            .title("천장에서 물이 새요")
            .build();
        conversation.updateLastMessageAt(NOON);

        ConversationListItemResponse item = ConversationListItemResponse.of(conversation, NOON.plusMinutes(5));

        assertThat(item.statusCode()).isEqualTo("CLOSED");
        assertThat(item.statusLabel()).isEqualTo("대화 종료");
        assertThat(item.closesAt().toLocalDateTime()).isEqualTo(NOON.plusMinutes(5));
    }
}
