package com.homes.zipsai.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.building.service.ResidentRoomService;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.common.repository.FileRepository;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutboxStatus;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.MessageFileGroup;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.conversation.dto.response.ConversationReplyResponse;
import com.homes.zipsai.conversation.dto.response.MessageResponse;
import com.homes.zipsai.conversation.outbox.ChatbotReplyWorkerProperties;
import com.homes.zipsai.conversation.repository.ChatbotReplyOutboxRepository;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.conversation.repository.MessageFileGroupRepository;
import com.homes.zipsai.conversation.repository.MessageRepository;
import com.homes.zipsai.migration.service.OutboxRecorder;

@ExtendWith(MockitoExtension.class)
class ConversationServiceAiReplyTest {

    private static final long CONVERSATION_ID = 10L;
    private static final long RESIDENT_MESSAGE_ID = 20L;
    private static final long EVENT_ID = 900L;
    private static final String TURN_ID = "turn-1";
    private static final LocalDateTime REPLIED_AT = LocalDateTime.of(2026, 9, 29, 12, 0);
    private static final List<AiConverseResponse.Citation> CITATIONS = List.of(
        new AiConverseResponse.Citation("building_document", "building-guide-12", "생활 안내", null, null));

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

    ConversationService conversationService;
    Conversation conversation;
    ChatbotReplyOutbox outbox;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(conversationRepository, messageRepository,
            messageFileGroupRepository, fileRepository, residentRoomService, s3StorageService,
            new StorageProperties(null, null, null, 300, 0), outboxRecorder, chatbotReplyOutboxRepository, null,
            new ChatbotReplyWorkerProperties(Duration.ofSeconds(130), Duration.ofSeconds(280)));
        conversation = Conversation.builder()
            .type(ConversationType.INQUIRY)
            .title("분리수거 요일이 언제인가요?")
            .build();
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
        outbox = processingOutbox();
    }

    @Test
    @DisplayName("AI 답변은 입주민 메시지와 같은 turn ID로 저장된다")
    void savesReplyWithResidentMessageTurnId() {
        givenReplySaved();

        conversationService.saveAiReply(pendingAiReply(), knowledge("화요일과 금요일입니다.", CITATIONS));

        assertThat(savedMessage().getTurnId()).isEqualTo(TURN_ID);
    }

    @Test
    @DisplayName("메시지 길이 제한보다 긴 AI 답변은 잘라서 저장한다")
    void trimsReplyLongerThanMessageLimit() {
        givenReplySaved();

        conversationService.saveAiReply(pendingAiReply(), knowledge("가".repeat(900), CITATIONS));

        assertThat(savedMessage().getContent()).hasSize(Message.CONTENT_MAX_LENGTH);
    }

    @Test
    @DisplayName("질의에 근거가 있으면 일반 답변으로 저장한다")
    void savesTextReplyWhenKnowledgeHasCitations() {
        givenReplySaved();

        ConversationReplyResponse reply =
            conversationService.saveAiReply(pendingAiReply(), knowledge("화요일과 금요일입니다.", CITATIONS))
                .orElseThrow();

        assertThat(reply.assistantMessage().messageType()).isEqualTo(MessageType.TEXT);

        assertThat(reply.assistantMessage().summaryCard()).isNull();
    }

    @Test
    @DisplayName("질의에 근거가 없으면 요약 카드로 저장한다")
    void savesSummaryCardWhenKnowledgeHasNoCitation() {
        givenReplySaved();

        ConversationReplyResponse reply =
            conversationService.saveAiReply(pendingAiReply(), knowledge("답변드리기 어렵습니다.", List.of()))
                .orElseThrow();

        assertThat(reply.assistantMessage().messageType()).isEqualTo(MessageType.SUMMARY_CARD);

        assertThat(reply.assistantMessage().summaryCard()).isNotNull();
    }

    @Test
    @DisplayName("AI 답변을 저장하면 답변 시각 5분 뒤를 종료 시각으로 돌려준다")
    void returnsClosingTimeFiveMinutesAfterReply() {
        givenReplySaved();

        ConversationReplyResponse reply =
            conversationService.saveAiReply(pendingAiReply(), knowledge("화요일과 금요일입니다.", CITATIONS))
                .orElseThrow();

        assertThat(reply.closesAt().toLocalDateTime()).isEqualTo(REPLIED_AT.plusMinutes(5));
    }

    @Test
    @DisplayName("요약 카드로 답하면 종료 시각이 없다")
    void returnsNoClosingTimeForSummaryCard() {
        givenReplySaved();

        ConversationReplyResponse reply =
            conversationService.saveAiReply(pendingAiReply(), knowledge("답변드리기 어렵습니다.", List.of()))
                .orElseThrow();

        assertThat(reply.closesAt()).isNull();
    }

    @Test
    @DisplayName("AI가 분석한 사진의 요약과 글자를 입주민 메시지 사진에 저장한다")
    void savesImageAnalysisOnResidentMessageImage() {
        givenReplySaved();
        MessageFileGroup fileGroup = givenResidentMessageImage(31L);

        conversationService.saveAiReply(pendingAiReply(),
            complaintWithImageAnalysis(new AiConverseResponse.ImageObservation(31L, "천장 얼룩", "관리실 010")));

        assertThat(fileGroup.getSummary()).isEqualTo("천장 얼룩");

        assertThat(fileGroup.getOcrText()).isEqualTo("관리실 010");
    }

    @Test
    @DisplayName("이번 메시지에 없는 사진의 분석 결과는 무시한다")
    void ignoresImageAnalysisOfOtherAttachment() {
        givenReplySaved();
        MessageFileGroup fileGroup = givenResidentMessageImage(31L);

        conversationService.saveAiReply(pendingAiReply(),
            complaintWithImageAnalysis(new AiConverseResponse.ImageObservation(99L, "천장 얼룩", null)));

        assertThat(fileGroup.getSummary()).isNull();

        assertThat(fileGroup.getOcrText()).isNull();
    }

    @Test
    @DisplayName("사진 분석 결과가 없으면 메시지 사진을 조회하지 않는다")
    void skipsImageLookupWithoutImageAnalysis() {
        givenReplySaved();

        conversationService.saveAiReply(pendingAiReply(), knowledge("화요일과 금요일입니다.", CITATIONS));

        then(messageFileGroupRepository).should(never()).findAllByMessageIds(any());
    }

    @Test
    @DisplayName("AI 답변을 저장하면 답변 이벤트를 완료로 바꾼다")
    void completesEventWhenReplyIsSaved() {
        givenReplySaved();

        conversationService.saveAiReply(pendingAiReply(), knowledge("화요일과 금요일입니다.", CITATIONS));

        assertThat(outbox.getStatus()).isEqualTo(ChatbotReplyOutboxStatus.COMPLETED);
    }

    @Test
    @DisplayName("다른 시도가 맡은 답변 이벤트면 AI 답변을 저장하지 않는다")
    void savesNoReplyWhenAnotherAttemptOwnsEvent() {
        givenEventTakenByAnotherAttempt();

        Optional<ConversationReplyResponse> reply =
            conversationService.saveAiReply(pendingAiReply(), knowledge("화요일과 금요일입니다.", CITATIONS));

        assertThat(reply).isEmpty();

        then(messageRepository).should(never()).save(any());

        assertThat(outbox.getStatus()).isEqualTo(ChatbotReplyOutboxStatus.PROCESSING);
    }

    @Test
    @DisplayName("실패 횟수가 같아도 인계 전 토큰의 답변은 저장하거나 대화에 반영하지 않는다")
    void savesNoReplyWhenTokenChangedWithoutAnotherFailure() {
        PendingAiReply pendingReply = pendingAiReply();
        outbox.retry(0);
        outbox.claim("next-token", REPLIED_AT.plusSeconds(1));
        givenEvent();

        Optional<ConversationReplyResponse> reply =
            conversationService.saveAiReply(pendingReply, knowledge("화요일과 금요일입니다.", CITATIONS));

        assertThat(reply).isEmpty();

        then(conversationRepository).shouldHaveNoInteractions();

        then(messageRepository).shouldHaveNoInteractions();

        assertThat(outbox.getStatus()).isEqualTo(ChatbotReplyOutboxStatus.PROCESSING);
    }

    @Test
    @DisplayName("답변받지 못한 메시지를 지우면 답변 이벤트를 실패로 바꾼다")
    void failsEventWhenUnansweredMessageIsDiscarded() {
        givenUnansweredEvent();

        conversationService.discardUnansweredMessage(EVENT_ID, 0, "lock-token");

        assertThat(outbox.getStatus()).isEqualTo(ChatbotReplyOutboxStatus.FAILED);
    }

    @Test
    @DisplayName("답변받지 못한 메시지를 지우면 실패를 알릴 사용자와 대화방을 돌려준다")
    void returnsUserAndConversationOfDiscardedMessage() {
        givenUnansweredEvent();

        Optional<DiscardedMessage> discarded = conversationService.discardUnansweredMessage(EVENT_ID, 0, "lock-token");

        assertThat(discarded).contains(new DiscardedMessage(outbox.getUserId(), CONVERSATION_ID));
    }

    @Test
    @DisplayName("다른 시도가 맡은 답변 이벤트면 메시지를 지우지 않는다")
    void keepsMessageWhenAnotherAttemptOwnsEvent() {
        givenEventTakenByAnotherAttempt();

        Optional<DiscardedMessage> discarded = conversationService.discardUnansweredMessage(EVENT_ID, 0, "lock-token");

        assertThat(discarded).isEmpty();

        then(messageRepository).should(never()).findById(any());

        then(messageRepository).should(never()).deleteById(any());

        then(conversationRepository).should(never()).deleteById(any());
    }

    @Test
    @DisplayName("실패 횟수가 같아도 인계 전 토큰의 실패 정리는 메시지와 대화를 변경하지 않는다")
    void keepsMessageWhenTokenChangedWithoutAnotherFailure() {
        outbox.retry(0);
        outbox.claim("next-token", REPLIED_AT.plusSeconds(1));
        givenEvent();

        Optional<DiscardedMessage> discarded =
            conversationService.discardUnansweredMessage(EVENT_ID, 0, "lock-token");

        assertThat(discarded).isEmpty();

        then(messageRepository).shouldHaveNoInteractions();

        then(conversationRepository).shouldHaveNoInteractions();

        assertThat(outbox.getStatus()).isEqualTo(ChatbotReplyOutboxStatus.PROCESSING);
    }

    @Test
    @DisplayName("첫 메시지의 답변을 받지 못하면 사진 연결, 메시지, 대화를 모두 지운다")
    void deletesConversationWhenFirstMessageIsUnanswered() {
        givenUnansweredEvent();

        conversationService.discardUnansweredMessage(EVENT_ID, 0, "lock-token");

        then(messageFileGroupRepository).should().deleteAllByMessageId(RESIDENT_MESSAGE_ID);

        then(messageRepository).should().deleteById(RESIDENT_MESSAGE_ID);

        then(conversationRepository).should().deleteById(CONVERSATION_ID);
    }

    @Test
    @DisplayName("후속 메시지의 답변을 받지 못하면 그 메시지만 지우고 마지막 메시지 시각을 되돌린다")
    void keepsConversationWhenFollowUpIsUnanswered() {
        LocalDateTime previousLastMessageAt = LocalDateTime.of(2026, 9, 27, 14, 0);
        conversation.updateLastMessageAt(LocalDateTime.of(2026, 9, 27, 15, 0));
        given(conversationRepository.findById(CONVERSATION_ID)).willReturn(Optional.of(conversation));
        outbox = followUpOutbox(previousLastMessageAt);
        givenUnansweredEvent();

        conversationService.discardUnansweredMessage(EVENT_ID, 0, "lock-token");

        then(messageRepository).should().deleteById(RESIDENT_MESSAGE_ID);

        then(conversationRepository).should(never()).deleteById(any());

        assertThat(conversation.getLastMessageAt()).isEqualTo(previousLastMessageAt);
    }

    private void givenUnansweredEvent() {
        givenEvent();
        given(messageRepository.findById(RESIDENT_MESSAGE_ID)).willReturn(Optional.of(residentMessage()));
    }

    private void givenEvent() {
        given(chatbotReplyOutboxRepository.findByIdForUpdate(EVENT_ID)).willReturn(Optional.of(outbox));
    }

    private void givenEventTakenByAnotherAttempt() {
        ReflectionTestUtils.setField(outbox, "attemptCount", 1);
        givenEvent();
    }

    private void givenReplySaved() {
        givenEvent();
        given(conversationRepository.findByResidentMessageForUpdate(RESIDENT_MESSAGE_ID, 1L))
            .willReturn(Optional.of(conversation));
        given(chatbotReplyOutboxRepository.currentTime()).willReturn(REPLIED_AT);
        given(messageRepository.save(any(Message.class))).willAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            ReflectionTestUtils.setField(message, "id", 21L);
            ReflectionTestUtils.setField(message, "createdAt", REPLIED_AT);
            return message;
        });
    }

    private MessageFileGroup givenResidentMessageImage(Long attachmentId) {
        File attachment = File.builder().fileKey("conversations/ceiling.jpg").fileType("jpg").build();
        ReflectionTestUtils.setField(attachment, "id", attachmentId);
        MessageFileGroup fileGroup = MessageFileGroup.builder().attachment(attachment).fileGroupSeq(1).build();
        given(messageFileGroupRepository.findAllByMessageIds(List.of(RESIDENT_MESSAGE_ID)))
            .willReturn(List.of(fileGroup));
        return fileGroup;
    }

    private Message savedMessage() {
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        then(messageRepository).should().save(messageCaptor.capture());

        return messageCaptor.getValue();
    }

    private PendingAiReply pendingAiReply() {
        MessageResponse residentMessage = new MessageResponse(RESIDENT_MESSAGE_ID, SenderType.RESIDENT,
            MessageType.TEXT, "분리수거 요일이 언제인가요?", List.of(), null, null);
        AiConverseRequest aiRequest =
            new AiConverseRequest(null, null, null, null, TURN_ID, null, null, null, null, List.of(), null);
        return new PendingAiReply(EVENT_ID, 0, "lock-token", 1L, conversation, residentMessage, aiRequest);
    }

    private ChatbotReplyOutbox processingOutbox() {
        ChatbotReplyOutbox outbox = ChatbotReplyOutbox.startFirstMessage(residentMessage(), 1L, "trace-1", "lock-token",
            REPLIED_AT);
        ReflectionTestUtils.setField(outbox, "id", EVENT_ID);
        ReflectionTestUtils.setField(outbox, "createdAt", REPLIED_AT);
        return outbox;
    }

    private ChatbotReplyOutbox followUpOutbox(LocalDateTime previousLastMessageAt) {
        ChatbotReplyOutbox outbox = ChatbotReplyOutbox.startNextMessage(residentMessage(), 1L, "trace-1", "lock-token",
            previousLastMessageAt, REPLIED_AT);
        ReflectionTestUtils.setField(outbox, "id", EVENT_ID);
        ReflectionTestUtils.setField(outbox, "createdAt", REPLIED_AT);
        return outbox;
    }

    private Message residentMessage() {
        Message residentMessage =
            new Message(conversation, "분리수거 요일이 언제인가요?", SenderType.RESIDENT, MessageType.TEXT, TURN_ID);
        ReflectionTestUtils.setField(residentMessage, "id", RESIDENT_MESSAGE_ID);
        return residentMessage;
    }

    private static AiConverseResponse complaintWithImageAnalysis(AiConverseResponse.ImageObservation observation) {
        AiConverseResponse.ImageAnalysis imageAnalysis = new AiConverseResponse.ImageAnalysis(List.of(observation));
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, TURN_ID,
            new AiConverseResponse.Data(AiRoute.COMPLAINT, null, "위치가 어디인가요?",
                AiConverseResponse.Result.builder()
                    .missingFields(List.of("location"))
                    .imageAnalysis(imageAnalysis)
                    .build()));
    }

    private static AiConverseResponse knowledge(String reply, List<AiConverseResponse.Citation> citations) {
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, TURN_ID,
            new AiConverseResponse.Data(AiRoute.KNOWLEDGE, null, reply,
                AiConverseResponse.Result.builder().citations(citations).build()));
    }
}
