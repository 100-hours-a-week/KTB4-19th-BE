package com.homes.zipsai.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

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
import com.homes.zipsai.common.repository.FileRepository;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.conversation.dto.response.MessageResponse;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.conversation.repository.MessageFileGroupRepository;
import com.homes.zipsai.conversation.repository.MessageRepository;

@ExtendWith(MockitoExtension.class)
class ConversationServiceAiReplyTest {

    private static final long CONVERSATION_ID = 10L;
    private static final long RESIDENT_MESSAGE_ID = 20L;
    private static final String TURN_ID = "turn-1";
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

    ConversationService conversationService;
    Conversation conversation;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(conversationRepository, messageRepository,
            messageFileGroupRepository, fileRepository, residentRoomService, s3StorageService,
            new StorageProperties(null, null, null, 300, 0));
        conversation = Conversation.builder()
            .type(ConversationType.INQUIRY)
            .title("분리수거 요일이 언제인가요?")
            .build();
        ReflectionTestUtils.setField(conversation, "id", CONVERSATION_ID);
    }

    @Test
    @DisplayName("AI 답변은 입주민 메시지와 같은 turn ID로 저장된다")
    void savesReplyWithResidentMessageTurnId() {
        givenReplySaved();

        conversationService.saveAiReply(pendingAiReply(true, null), knowledge("화요일과 금요일입니다.", CITATIONS));

        assertThat(savedMessage().getTurnId()).isEqualTo(TURN_ID);
    }

    @Test
    @DisplayName("메시지 길이 제한보다 긴 AI 답변은 잘라서 저장한다")
    void trimsReplyLongerThanMessageLimit() {
        givenReplySaved();

        conversationService.saveAiReply(pendingAiReply(true, null), knowledge("가".repeat(900), CITATIONS));

        assertThat(savedMessage().getContent()).hasSize(Message.CONTENT_MAX_LENGTH);
    }

    @Test
    @DisplayName("질의에 근거가 있으면 일반 답변으로 저장한다")
    void savesTextReplyWhenKnowledgeHasCitations() {
        givenReplySaved();

        MessageResponse reply =
            conversationService.saveAiReply(pendingAiReply(true, null), knowledge("화요일과 금요일입니다.", CITATIONS));

        assertThat(reply.messageType()).isEqualTo(MessageType.TEXT);
        assertThat(reply.summaryCard()).isNull();
    }

    @Test
    @DisplayName("질의에 근거가 없으면 요약 카드로 저장한다")
    void savesSummaryCardWhenKnowledgeHasNoCitation() {
        givenReplySaved();

        MessageResponse reply =
            conversationService.saveAiReply(pendingAiReply(true, null), knowledge("답변드리기 어렵습니다.", List.of()));

        assertThat(reply.messageType()).isEqualTo(MessageType.SUMMARY_CARD);
        assertThat(reply.summaryCard()).isNotNull();
    }

    @Test
    @DisplayName("첫 메시지의 답변을 받지 못하면 사진 연결, 메시지, 대화를 모두 지운다")
    void deletesConversationWhenFirstMessageIsUnanswered() {
        conversationService.discardUnansweredMessage(pendingAiReply(true, null));

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

        conversationService.discardUnansweredMessage(pendingAiReply(false, previousLastMessageAt));

        then(messageRepository).should().deleteById(RESIDENT_MESSAGE_ID);
        then(conversationRepository).should(never()).deleteById(any());
        assertThat(conversation.getLastMessageAt()).isEqualTo(previousLastMessageAt);
    }

    private void givenReplySaved() {
        given(conversationRepository.getReferenceById(CONVERSATION_ID)).willReturn(conversation);
        given(messageRepository.save(any(Message.class))).willAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            ReflectionTestUtils.setField(message, "id", 21L);
            return message;
        });
    }

    private Message savedMessage() {
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        then(messageRepository).should().save(messageCaptor.capture());
        return messageCaptor.getValue();
    }

    private PendingAiReply pendingAiReply(boolean newConversation, LocalDateTime previousLastMessageAt) {
        MessageResponse residentMessage = new MessageResponse(RESIDENT_MESSAGE_ID, SenderType.RESIDENT,
            MessageType.TEXT, "분리수거 요일이 언제인가요?", List.of(), null, null);
        AiConverseRequest aiRequest =
            new AiConverseRequest(null, null, null, null, TURN_ID, null, null, null, List.of(), null);
        return new PendingAiReply(conversation, residentMessage, newConversation, previousLastMessageAt, aiRequest);
    }

    private static AiConverseResponse knowledge(String reply, List<AiConverseResponse.Citation> citations) {
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, TURN_ID,
            new AiConverseResponse.Data(AiRoute.KNOWLEDGE, null, reply,
                new AiConverseResponse.Result(null, null, List.of(), citations)));
    }
}
