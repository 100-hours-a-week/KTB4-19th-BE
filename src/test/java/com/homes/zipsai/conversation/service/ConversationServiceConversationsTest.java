package com.homes.zipsai.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.building.service.ResidentRoomService;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.repository.FileRepository;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.dto.response.ConversationListItemResponse;
import com.homes.zipsai.conversation.dto.response.ConversationListResponse;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.conversation.repository.MessageFileGroupRepository;
import com.homes.zipsai.conversation.repository.MessageRepository;

@ExtendWith(MockitoExtension.class)
class ConversationServiceConversationsTest {

    private static final long RESIDENT_ID = 1L;
    private static final LocalDateTime NOON = LocalDateTime.of(2026, 9, 27, 12, 0);

    @Mock
    ConversationRepository conversationRepository;

    @Mock
    MessageRepository messageRepository;

    @Mock
    MessageFileGroupRepository messageFileGroupRepository;

    @Mock
    FileRepository fileRepository;

    @Mock
    ResidentRoomService residentRoomService;

    @Mock
    S3StorageService s3StorageService;

    ConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(conversationRepository, messageRepository,
            messageFileGroupRepository, fileRepository, residentRoomService, s3StorageService,
            new StorageProperties(null, null, null, 300, 0));
    }

    @Test
    @DisplayName("대화가 조회 개수보다 많으면 마지막으로 보여준 대화 위치를 다음 커서로 준다")
    void returnsLastShownConversationAsNextCursorWhenMoreExist() {
        Conversation lastShown = conversation(11L, NOON.plusHours(1));
        givenLatestConversations(conversation(12L, NOON.plusHours(2)), lastShown, conversation(10L, NOON));

        ConversationListResponse response = conversationService.getConversations(RESIDENT_ID, null, null, 2);

        assertThat(response.hasNext()).isTrue();
        assertThat(response.conversations()).extracting(ConversationListItemResponse::conversationId)
            .containsExactly(12L, 11L);
        assertThat(ConversationCursor.parse(response.nextCursor()))
            .isEqualTo(new ConversationCursor(lastShown.getLastMessageAt(), 11L));
    }

    @Test
    @DisplayName("대화가 조회 개수 이하면 다음 커서가 없다")
    void returnsNoCursorOnLastConversationPage() {
        givenLatestConversations(conversation(12L, NOON.plusHours(2)), conversation(11L, NOON.plusHours(1)));

        ConversationListResponse response = conversationService.getConversations(RESIDENT_ID, null, null, 2);

        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("커서를 보내면 커서 위치보다 먼저 대화한 대화를 조회한다")
    void findsConversationsBeforeCursorPosition() {
        String cursor = new ConversationCursor(NOON.plusHours(1), 11L).encode();
        given(conversationRepository.findLatestByUserIdBefore(RESIDENT_ID, null, NOON.plusHours(1), 11L, Limit.of(3)))
            .willReturn(List.of(conversation(10L, NOON)));

        ConversationListResponse response = conversationService.getConversations(RESIDENT_ID, null, cursor, 2);

        assertThat(response.conversations()).extracting(ConversationListItemResponse::conversationId)
            .containsExactly(10L);
    }

    private void givenLatestConversations(Conversation... conversations) {
        given(conversationRepository.findLatestByUserId(RESIDENT_ID, null, Limit.of(3)))
            .willReturn(List.of(conversations));
    }

    private static Conversation conversation(long id, LocalDateTime lastMessageAt) {
        Conversation conversation = Conversation.builder()
            .type(ConversationType.INQUIRY)
            .title("천장에서 물이 새요")
            .build();
        conversation.updateLastMessageAt(lastMessageAt);
        ReflectionTestUtils.setField(conversation, "id", id);
        return conversation;
    }
}
