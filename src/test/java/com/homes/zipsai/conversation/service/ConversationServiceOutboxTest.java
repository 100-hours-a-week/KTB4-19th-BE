package com.homes.zipsai.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.service.ResidentRoomService;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.repository.FileRepository;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.conversation.repository.ChatbotReplyOutboxRepository;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.conversation.repository.MessageFileGroupRepository;
import com.homes.zipsai.conversation.repository.MessageRepository;
import com.homes.zipsai.migration.service.OutboxRecorder;
import com.homes.zipsai.user.domain.User;

@ExtendWith(MockitoExtension.class)
class ConversationServiceOutboxTest {

    private static final long RESIDENT_ID = 7L;
    private static final long CONVERSATION_ID = 42L;
    private static final long MESSAGE_ID = 500L;
    private static final long EVENT_ID = 900L;
    private static final String LOCK_TOKEN = "lock-token";
    private static final String TRACE_ID = "trace-1";

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

    @Mock
    OutboxRecorder outboxRecorder;

    @Mock
    ChatbotReplyOutboxRepository chatbotReplyOutboxRepository;

    @Mock
    ConversationReplyLock conversationReplyLock;

    ConversationService conversationService;
    User resident;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(conversationRepository, messageRepository,
            messageFileGroupRepository, fileRepository, residentRoomService, s3StorageService,
            new StorageProperties(null, null, null, 300, 0), outboxRecorder, chatbotReplyOutboxRepository,
            conversationReplyLock);
        resident = withId(new User("resident@example.com", "password", "입주민", null), RESIDENT_ID);
        MDC.put("traceId", TRACE_ID);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("후속 메시지의 답변 이벤트에 받은 락 토큰을 남기고 같은 값을 응답 처리에 넘긴다")
    void savesProcessingEventWithLockTokenForNextMessage() {
        givenLivingRoom();
        givenOwnedConversation();
        givenSavedMessageAndEvent();

        PendingAiReply pendingReply =
            conversationService.saveNextMessage(RESIDENT_ID, CONVERSATION_ID, "온수도 안 나와요", null, LOCK_TOKEN);

        assertThat(savedEvent())
            .hasFieldOrPropertyWithValue("messageId", MESSAGE_ID)
            .hasFieldOrPropertyWithValue("userId", RESIDENT_ID)
            .hasFieldOrPropertyWithValue("traceId", TRACE_ID)
            .hasFieldOrPropertyWithValue("lockToken", LOCK_TOKEN);
        assertThat(pendingReply)
            .hasFieldOrPropertyWithValue("eventId", EVENT_ID)
            .hasFieldOrPropertyWithValue("attempt", 0)
            .hasFieldOrPropertyWithValue("lockToken", LOCK_TOKEN)
            .hasFieldOrPropertyWithValue("userId", RESIDENT_ID);
    }

    @Test
    @DisplayName("새 대화방은 대화방을 저장한 직후 답변 잠금을 잡고 그 뒤에 메시지를 저장한다")
    void acquiresReplyLockRightAfterSavingNewConversation() {
        givenLivingRoom();
        givenSavedConversation();
        given(conversationReplyLock.acquire(CONVERSATION_ID)).willReturn(LOCK_TOKEN);
        givenSavedMessageAndEvent();

        PendingAiReply pendingReply = conversationService.saveFirstMessage(RESIDENT_ID, "보일러가 안 켜져요", null);

        InOrder inOrder = inOrder(conversationRepository, conversationReplyLock, messageRepository);
        inOrder.verify(conversationRepository).save(any(Conversation.class));
        inOrder.verify(conversationReplyLock).acquire(CONVERSATION_ID);
        inOrder.verify(messageRepository).save(any(Message.class));
        assertThat(savedEvent().getLockToken()).isEqualTo(LOCK_TOKEN);
        assertThat(pendingReply.lockToken()).isEqualTo(LOCK_TOKEN);
    }

    @Test
    @DisplayName("재시도는 이벤트에 남은 메시지와 사용자로 AI 요청을 다시 만들고 넘겨받은 시도 번호와 락 토큰을 담는다")
    void preparesRetryFromStoredEvent() {
        givenLivingRoom();
        ChatbotReplyOutbox outbox = givenStoredEvent(1);
        given(messageRepository.findById(MESSAGE_ID)).willReturn(Optional.of(storedResidentMessage()));

        PendingAiReply pendingReply = conversationService.prepareReply(EVENT_ID, 1, "retry-token").orElseThrow();

        assertThat(pendingReply)
            .hasFieldOrPropertyWithValue("eventId", EVENT_ID)
            .hasFieldOrPropertyWithValue("attempt", 1)
            .hasFieldOrPropertyWithValue("lockToken", "retry-token")
            .hasFieldOrPropertyWithValue("userId", RESIDENT_ID);
        assertThat(pendingReply.aiRequest().turnId()).isEqualTo("turn-1");
        assertThat(pendingReply.aiRequest().traceId()).isEqualTo(outbox.getTraceId());
        then(messageRepository).should().findAllByConversationIdBefore(CONVERSATION_ID, MESSAGE_ID);
    }

    @Test
    @DisplayName("재시도할 이벤트를 다른 시도가 맡았으면 재료를 읽지 않고 빈 값을 돌려준다")
    void preparesNothingWhenAnotherAttemptOwnsEvent() {
        givenStoredEvent(2);

        assertThat(conversationService.prepareReply(EVENT_ID, 1, "retry-token")).isEmpty();
        then(messageRepository).should(never()).findById(any());
    }

    private void givenLivingRoom() {
        given(residentRoomService.getLivingRoom(RESIDENT_ID)).willReturn(livingRoom(resident));
    }

    private ChatbotReplyOutbox givenStoredEvent(int attemptCount) {
        ChatbotReplyOutbox outbox = withId(ChatbotReplyOutbox.startNextMessage(storedResidentMessage(), RESIDENT_ID,
            "trace-retry", "old-token", null, LocalDateTime.of(2026, 10, 22, 10, 7)), EVENT_ID);
        ReflectionTestUtils.setField(outbox, "attemptCount", attemptCount);
        given(chatbotReplyOutboxRepository.findById(EVENT_ID)).willReturn(Optional.of(outbox));
        return outbox;
    }

    private Message storedResidentMessage() {
        Conversation conversation =
            withId(new Conversation(resident, ConversationType.INQUIRY, "보일러가 안 켜져요"), CONVERSATION_ID);
        return withId(new Message(conversation, "기사님 언제 오세요?", SenderType.RESIDENT, MessageType.TEXT, "turn-1"),
            MESSAGE_ID);
    }

    private void givenOwnedConversation() {
        Conversation conversation =
            withId(new Conversation(resident, ConversationType.INQUIRY, "보일러가 안 켜져요"), CONVERSATION_ID);
        given(conversationRepository.findByIdAndDeletedAtIsNull(CONVERSATION_ID)).willReturn(Optional.of(conversation));
    }

    private void givenSavedConversation() {
        given(conversationRepository.save(any(Conversation.class)))
            .willAnswer(invocation -> withId(invocation.getArgument(0), CONVERSATION_ID));
    }

    private void givenSavedMessageAndEvent() {
        given(messageRepository.save(any(Message.class)))
            .willAnswer(invocation -> withId(invocation.getArgument(0), MESSAGE_ID));
        given(chatbotReplyOutboxRepository.save(any(ChatbotReplyOutbox.class)))
            .willAnswer(invocation -> withId(invocation.getArgument(0), EVENT_ID));
    }

    private ChatbotReplyOutbox savedEvent() {
        ArgumentCaptor<ChatbotReplyOutbox> eventCaptor = ArgumentCaptor.forClass(ChatbotReplyOutbox.class);
        then(chatbotReplyOutboxRepository).should().save(eventCaptor.capture());
        return eventCaptor.getValue();
    }

    private static Room livingRoom(User resident) {
        Room room = new Room(new Building(withId(new User("manager@example.com", "password", "관리자", null), 99L),
            "서울시 테스트로 1", "테스트빌"), "302");
        room.invite();
        room.moveIn(resident);
        return room;
    }

    private static <T> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
