package com.homes.zipsai.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import com.homes.zipsai.user.domain.User;

@ExtendWith(MockitoExtension.class)
class ConversationServiceSummaryCardTest {

    private static final long RESIDENT_ID = 1L;
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
    @DisplayName("요약 카드는 대화에 올린 사진 수를 보여준다")
    void showsConversationImageCountOnSummaryCard() {
        givenMessages(message(21L, MessageType.SUMMARY_CARD));
        given(messageFileGroupRepository.countByConversationId(CONVERSATION_ID)).willReturn(2L);

        ConversationMessagesResponse response = conversationService.getMessages(RESIDENT_ID, CONVERSATION_ID, null, 20);

        assertThat(response.messages()).singleElement()
            .extracting(MessageResponse::summaryCard)
            .extracting(summaryCard -> summaryCard.attachmentCount())
            .isEqualTo(2L);
    }

    @Test
    @DisplayName("요약 카드가 없으면 사진 수를 세지 않는다")
    void skipsImageCountWithoutSummaryCard() {
        givenMessages(message(20L, MessageType.TEXT));

        ConversationMessagesResponse response = conversationService.getMessages(RESIDENT_ID, CONVERSATION_ID, null, 20);

        assertThat(response.messages()).singleElement()
            .extracting(MessageResponse::summaryCard)
            .isNull();
        then(messageFileGroupRepository).should(never()).countByConversationId(any());
    }

    private void givenMessages(Message... messages) {
        given(conversationRepository.findByIdAndDeletedAtIsNull(CONVERSATION_ID)).willReturn(Optional.of(conversation));
        given(messageRepository.findLatestByConversationId(any(), any())).willReturn(List.of(messages));
    }

    private Message message(long id, MessageType messageType) {
        return withId(Message.builder()
            .conversation(conversation)
            .senderType(SenderType.ASSISTANT)
            .messageType(messageType)
            .content("접수 내용을 확인해 주세요")
            .build(), id);
    }

    private static <T> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
