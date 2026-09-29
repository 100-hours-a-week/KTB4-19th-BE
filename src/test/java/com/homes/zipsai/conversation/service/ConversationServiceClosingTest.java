package com.homes.zipsai.conversation.service;

import static org.mockito.BDDMockito.then;

import java.time.LocalDateTime;

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
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.conversation.repository.MessageFileGroupRepository;
import com.homes.zipsai.conversation.repository.MessageRepository;

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

    ConversationService conversationService;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(conversationRepository, messageRepository,
            messageFileGroupRepository, fileRepository, residentRoomService, s3StorageService,
            new StorageProperties(null, null, null, 300, 0));
    }

    @Test
    @DisplayName("지금보다 5분 전까지 마지막 메시지가 있던 대화를 종료 대상으로 넘긴다")
    void closesConversationsLastMessagedFiveMinutesAgo() {
        conversationService.closeIdleConversations(NOON);

        then(conversationRepository).should().closeIdleConversations(NOON.minusMinutes(5), NOON);
    }
}
