package com.homes.zipsai.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Complaint;
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
class ManagerConversationReadTest {

    private static final long MANAGER_ID = 9L;
    private static final long OTHER_MANAGER_ID = 8L;
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
        conversation = conversation();
    }

    @Test
    @DisplayName("담당 건물의 민원이 접수된 대화를 읽는다")
    void readsConversationOfOwnBuildingComplaint() {
        givenConversationExists();
        givenComplaintOwnedBy(MANAGER_ID);
        given(messageRepository.findLatestByConversationId(any(), any()))
            .willReturn(List.of(message("천장에서 물이 새요")));

        ConversationMessagesResponse response = conversationService.getComplaintMessagesForManager(
            MANAGER_ID, CONVERSATION_ID, null, 20);

        assertThat(response.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(response.messages()).singleElement()
            .extracting(MessageResponse::content)
            .isEqualTo("천장에서 물이 새요");
    }

    @Test
    @DisplayName("다른 관리자의 건물에 접수된 대화는 읽을 수 없다")
    void rejectsConversationOfAnotherManagerBuilding() {
        givenConversationExists();
        givenComplaintOwnedBy(OTHER_MANAGER_ID);

        assertThatThrownBy(() -> conversationService.getComplaintMessagesForManager(
            MANAGER_ID, CONVERSATION_ID, null, 20))
            .isInstanceOf(ForbiddenException.class)
            .hasFieldOrPropertyWithValue("code", "FORBIDDEN");
    }

    @Test
    @DisplayName("민원이 접수되지 않은 대화는 찾을 수 없다")
    void rejectsConversationWithoutComplaint() {
        givenConversationExists();
        given(conversationRepository.findComplaintByConversationId(CONVERSATION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.getComplaintMessagesForManager(
            MANAGER_ID, CONVERSATION_ID, null, 20))
            .isInstanceOf(NotFoundException.class)
            .hasFieldOrPropertyWithValue("code", "CONVERSATION_NOT_FOUND");
    }

    @Test
    @DisplayName("삭제된 대화는 찾을 수 없다")
    void rejectsDeletedConversation() {
        given(conversationRepository.findByIdAndDeletedAtIsNull(CONVERSATION_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.getComplaintMessagesForManager(
            MANAGER_ID, CONVERSATION_ID, null, 20))
            .isInstanceOf(NotFoundException.class)
            .hasFieldOrPropertyWithValue("code", "CONVERSATION_NOT_FOUND");
    }

    private void givenConversationExists() {
        given(conversationRepository.findByIdAndDeletedAtIsNull(CONVERSATION_ID))
            .willReturn(Optional.of(conversation));
    }

    private void givenComplaintOwnedBy(long managerId) {
        given(conversationRepository.findComplaintByConversationId(CONVERSATION_ID))
            .willReturn(Optional.of(complaint(managerId)));
    }

    private Complaint complaint(long managerId) {
        return withId(Complaint.builder()
            .conversation(conversation)
            .user(user(RESIDENT_ID))
            .building(withId(new Building(user(managerId), "서울시 테스트로 1", "테스트빌"), 7L))
            .title("천장에서 물이 새요")
            .urgency(0)
            .roomNo("302")
            .build(), 100L);
    }

    private static Conversation conversation() {
        return withId(Conversation.builder()
            .user(user(RESIDENT_ID))
            .type(ConversationType.COMPLAINT)
            .title("천장에서 물이 새요")
            .build(), CONVERSATION_ID);
    }

    private Message message(String content) {
        return withId(Message.builder()
            .conversation(conversation)
            .senderType(SenderType.RESIDENT)
            .messageType(MessageType.TEXT)
            .content(content)
            .build(), 20L);
    }

    private static User user(long id) {
        return withId(new User(id + "@example.com", "password", "사용자", null), id);
    }

    private static <T> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
