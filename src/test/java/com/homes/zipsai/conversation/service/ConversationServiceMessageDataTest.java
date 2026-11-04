package com.homes.zipsai.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import com.homes.zipsai.conversation.ai.AiComplaintState;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.conversation.outbox.ChatbotReplyWorkerProperties;
import com.homes.zipsai.conversation.repository.ChatbotReplyOutboxRepository;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.conversation.repository.MessageFileGroupRepository;
import com.homes.zipsai.conversation.repository.MessageRepository;
import com.homes.zipsai.migration.service.OutboxRecorder;
import com.homes.zipsai.user.domain.User;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ConversationServiceMessageDataTest {

    private static final long RESIDENT_ID = 7L;
    private static final long CONVERSATION_ID = 42L;
    private static final long MESSAGE_ID = 500L;
    private static final long EVENT_ID = 900L;
    private static final String LOCK_TOKEN = "lock-token";

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

    ObjectMapper objectMapper = JsonMapper.builder().build();
    ConversationService conversationService;
    User resident;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(conversationRepository, messageRepository,
            messageFileGroupRepository, fileRepository, residentRoomService, s3StorageService,
            new StorageProperties(null, null, null, 300, 0), outboxRecorder, chatbotReplyOutboxRepository,
            conversationReplyLock,
            new ChatbotReplyWorkerProperties(Duration.ofSeconds(130), Duration.ofSeconds(280)), objectMapper);
        resident = withId(new User("resident@example.com", "password", "입주민", null), RESIDENT_ID);
        MDC.put("traceId", "trace-1");
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("첫 메시지는 AI에 보낼 요청 JSON을 그대로 답변 이벤트에 저장한다")
    void savesFirstMessageRequestAsMessageData() {
        givenLivingRoom();
        given(conversationRepository.save(any(Conversation.class)))
            .willAnswer(invocation -> withId(invocation.getArgument(0), CONVERSATION_ID));
        given(conversationReplyLock.acquire(CONVERSATION_ID)).willReturn(LOCK_TOKEN);
        givenSavedMessageAndEvent();

        PendingAiReply pendingReply = conversationService.saveFirstMessage(RESIDENT_ID, "보일러가 안 켜져요", null);

        assertThat(savedEvent().getMessageData())
            .isEqualTo(objectMapper.writeValueAsString(pendingReply.aiRequest()));
    }

    @Test
    @DisplayName("후속 메시지는 이전 대화가 담긴 AI 요청 JSON을 그대로 답변 이벤트에 저장한다")
    void savesNextMessageRequestWithHistoryAsMessageData() {
        givenLivingRoom();
        Conversation conversation = givenOwnedConversation();
        givenPreviousMessages(conversation);
        givenSavedMessageAndEvent();

        PendingAiReply pendingReply =
            conversationService.saveNextMessage(RESIDENT_ID, CONVERSATION_ID, "온수도 안 나와요", null, LOCK_TOKEN);

        String messageData = savedEvent().getMessageData();

        assertThat(messageData).isEqualTo(objectMapper.writeValueAsString(pendingReply.aiRequest()));

        assertThat(objectMapper.readTree(messageData).get("conversation_history")).hasSize(2);
    }

    @Test
    @DisplayName("저장한 요청의 대화 단계와 민원 초안 발생 시각은 AI에 보내는 값 그대로 남는다")
    void keepsRouteAndDraftValuesInMessageData() {
        givenLivingRoom();
        Conversation conversation = givenOwnedConversation();
        ReflectionTestUtils.setField(conversation, "currentRoute", AiRoute.COMPLAINT);
        ReflectionTestUtils.setField(conversation, "complaintState", AiComplaintState.COLLECTING);
        ReflectionTestUtils.setField(conversation, "draftOccurredAt",
            OffsetDateTime.of(2026, 11, 4, 9, 0, 0, 0, ZoneOffset.ofHours(9)));
        ReflectionTestUtils.setField(conversation, "draftAttachmentIds", List.of(11L, 12L));
        givenSavedMessageAndEvent();

        conversationService.saveNextMessage(RESIDENT_ID, CONVERSATION_ID, "온수도 안 나와요", null, LOCK_TOKEN);

        JsonNode messageData = objectMapper.readTree(savedEvent().getMessageData());

        assertThat(messageData.get("current_route").asString()).isEqualTo("complaint");

        assertThat(messageData.get("current_complaint_state").asString()).isEqualTo("collecting");

        assertThat(messageData.at("/complaint_draft/occurred_at").asString()).isEqualTo("2026-11-04T09:00:00+09:00");

        assertThat(messageData.at("/complaint_draft/attachmentIds/1").asLong()).isEqualTo(12L);
    }

    private void givenLivingRoom() {
        given(residentRoomService.getLivingRoom(RESIDENT_ID)).willReturn(livingRoom(resident));
    }

    private Conversation givenOwnedConversation() {
        Conversation conversation =
            withId(new Conversation(resident, ConversationType.INQUIRY, "보일러가 안 켜져요"), CONVERSATION_ID);
        given(conversationRepository.findByIdAndDeletedAtIsNull(CONVERSATION_ID)).willReturn(Optional.of(conversation));
        return conversation;
    }

    private void givenPreviousMessages(Conversation conversation) {
        Message question =
            withId(new Message(conversation, "보일러가 안 켜져요", SenderType.RESIDENT, MessageType.TEXT, "turn-1"), 498L);
        Message answer =
            withId(new Message(conversation, "전원을 확인해 주세요", SenderType.ASSISTANT, MessageType.TEXT, "turn-1"), 499L);
        given(messageRepository.findAllByConversationIdBefore(CONVERSATION_ID, MESSAGE_ID))
            .willReturn(List.of(question, answer));
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
