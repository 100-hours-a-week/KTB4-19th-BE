package com.homes.zipsai.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Optional;

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
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.conversation.dto.response.ConversationMessagesResponse;
import com.homes.zipsai.conversation.dto.response.MessageResponse;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.conversation.repository.MessageFileGroupRepository;
import com.homes.zipsai.conversation.repository.MessageRepository;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.global.exception.NotFoundException;
import com.homes.zipsai.user.domain.User;

@ExtendWith(MockitoExtension.class)
class ConversationServiceMessagesTest {

    private static final long RESIDENT_ID = 1L;
    private static final long OTHER_RESIDENT_ID = 2L;
    private static final long CONVERSATION_ID = 10L;

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
    Conversation conversation;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(conversationRepository, messageRepository,
            messageFileGroupRepository, fileRepository, residentRoomService, s3StorageService,
            new StorageProperties(null, null, null, 300, 0));
        conversation = withId(Conversation.builder()
            .user(withId(new User("resident@example.com", "password", "입주민", null), RESIDENT_ID))
            .type(ConversationType.INQUIRY)
            .title("천장에서 물이 새요")
            .build(), CONVERSATION_ID);
    }

    @Test
    @DisplayName("없는 대화의 메시지는 조회할 수 없다")
    void rejectsUnknownConversation() {
        given(conversationRepository.findByIdAndDeletedAtIsNull(CONVERSATION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.getMessages(RESIDENT_ID, CONVERSATION_ID, null, 20))
            .isInstanceOf(NotFoundException.class)
            .hasFieldOrPropertyWithValue("code", "CONVERSATION_NOT_FOUND");
    }

    @Test
    @DisplayName("다른 입주민의 대화 메시지는 조회할 수 없다")
    void rejectsOtherResidentsConversation() {
        given(conversationRepository.findByIdAndDeletedAtIsNull(CONVERSATION_ID)).willReturn(Optional.of(conversation));

        assertThatThrownBy(() -> conversationService.getMessages(OTHER_RESIDENT_ID, CONVERSATION_ID, null, 20))
            .isInstanceOf(ForbiddenException.class)
            .hasFieldOrPropertyWithValue("code", "FORBIDDEN");
    }

    @Test
    @DisplayName("메시지가 조회 개수보다 많으면 다음 커서로 가장 오래된 메시지 ID를 준다")
    void returnsOldestMessageIdAsNextCursorWhenMoreExist() {
        givenLatestMessages(message(30L), message(29L), message(28L));

        ConversationMessagesResponse response = conversationService.getMessages(RESIDENT_ID, CONVERSATION_ID, null, 2);

        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(29L);
    }

    @Test
    @DisplayName("메시지가 조회 개수 이하면 다음 커서가 없다")
    void returnsNoCursorOnLastPage() {
        givenLatestMessages(message(30L), message(29L));

        ConversationMessagesResponse response = conversationService.getMessages(RESIDENT_ID, CONVERSATION_ID, null, 2);

        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("최신 메시지를 조회해 오래된 순으로 돌려준다")
    void returnsMessagesOldestFirst() {
        givenLatestMessages(message(30L), message(29L));

        ConversationMessagesResponse response = conversationService.getMessages(RESIDENT_ID, CONVERSATION_ID, null, 2);

        assertThat(response.messages()).extracting(MessageResponse::messageId).containsExactly(29L, 30L);
    }

    private void givenLatestMessages(Message... messages) {
        given(conversationRepository.findByIdAndDeletedAtIsNull(CONVERSATION_ID)).willReturn(Optional.of(conversation));
        given(messageRepository.findLatestByConversationId(CONVERSATION_ID, Limit.of(3))).willReturn(List.of(messages));
    }

    private Message message(long id) {
        return withId(Message.builder()
            .conversation(conversation)
            .senderType(SenderType.RESIDENT)
            .messageType(MessageType.TEXT)
            .content("천장에서 물이 새요")
            .build(), id);
    }

    private static <T> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
