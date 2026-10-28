package com.homes.zipsai.conversation.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.homes.zipsai.building.service.ResidentRoomService;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.repository.FileRepository;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.outbox.ChatbotReplyWorkerProperties;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.conversation.repository.MessageFileGroupRepository;
import com.homes.zipsai.conversation.repository.MessageRepository;
import com.homes.zipsai.migration.domain.OutboxOperation;
import com.homes.zipsai.migration.service.OutboxRecorder;

@ExtendWith(MockitoExtension.class)
class ConversationServiceClosingTest {

    private static final LocalDateTime NOON = LocalDateTime.of(2026, 9, 29, 12, 0);

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

    ConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(conversationRepository, messageRepository,
            messageFileGroupRepository, fileRepository, residentRoomService, s3StorageService,
            new StorageProperties(null, null, null, 300, 0), outboxRecorder, null, null,
            new ChatbotReplyWorkerProperties(Duration.ofSeconds(130), Duration.ofSeconds(280)));
    }

    @Test
    @DisplayName("지금보다 5분 전까지 마지막 메시지가 있던 대화를 찾아 종료한다")
    void closesConversationsLastMessagedFiveMinutesAgo() {
        given(conversationRepository.findIdleConversationIds(NOON.minusMinutes(5))).willReturn(List.of(1L, 2L));

        conversationService.closeIdleConversations(NOON);

        then(conversationRepository).should().closeIdleConversations(List.of(1L, 2L), NOON.minusMinutes(5), NOON);
    }

    @Test
    @DisplayName("종료한 대화를 이전 이벤트로 기록한다")
    void recordsClosedConversationsToOutbox() {
        given(conversationRepository.findIdleConversationIds(NOON.minusMinutes(5))).willReturn(List.of(1L, 2L));

        conversationService.closeIdleConversations(NOON);

        then(outboxRecorder).should().recordAll(Conversation.class, List.of(1L, 2L), OutboxOperation.UPDATE);
    }

    @Test
    @DisplayName("종료할 대화가 없으면 종료 쿼리를 실행하지 않는다")
    void skipsClosingWhenNoIdleConversation() {
        given(conversationRepository.findIdleConversationIds(NOON.minusMinutes(5))).willReturn(List.of());

        conversationService.closeIdleConversations(NOON);

        then(conversationRepository).should(never()).closeIdleConversations(any(), any(), any());
        then(outboxRecorder).shouldHaveNoInteractions();
    }
}
