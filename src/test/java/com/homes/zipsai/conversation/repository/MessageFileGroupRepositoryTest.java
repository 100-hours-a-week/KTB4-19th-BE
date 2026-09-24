package com.homes.zipsai.conversation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Limit;

import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.common.repository.FileRepository;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.MessageFileGroup;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.repository.UserRepository;

@DataJpaTest
class MessageFileGroupRepositoryTest {

    @Autowired
    MessageFileGroupRepository messageFileGroupRepository;

    @Autowired
    MessageRepository messageRepository;

    @Autowired
    ConversationRepository conversationRepository;

    @Autowired
    FileRepository fileRepository;

    @Autowired
    UserRepository userRepository;

    private Conversation conversation;

    @BeforeEach
    void setUp() {
        User resident = userRepository.save(new User("resident@example.com", "password", "입주민", null));
        conversation = conversationRepository.save(Conversation.builder()
            .user(resident)
            .type(ConversationType.INQUIRY)
            .title("천장에서 물이 새요")
            .build());
    }

    @Test
    @DisplayName("대화의 사진을 하나만 조회하면 가장 먼저 올린 사진이 나온다")
    void returnsEarliestImageWhenLimitedToOne() {
        Message firstMessage = saveMessage();
        Message secondMessage = saveMessage();
        File earliest = saveFile("earliest");
        attach(secondMessage, saveFile("later-message"), 1);
        attach(firstMessage, saveFile("second-seq"), 2);
        attach(firstMessage, earliest, 1);

        List<MessageFileGroup> found =
            messageFileGroupRepository.findAllByConversationId(conversation.getId(), Limit.of(1));

        assertThat(found).singleElement()
            .extracting(MessageFileGroup::getAttachment)
            .isEqualTo(earliest);
    }

    @Test
    @DisplayName("사진이 없는 대화는 빈 목록을 돌려준다")
    void returnsEmptyWhenConversationHasNoImage() {
        saveMessage();

        List<MessageFileGroup> found =
            messageFileGroupRepository.findAllByConversationId(conversation.getId(), Limit.of(1));

        assertThat(found).isEmpty();
    }

    private Message saveMessage() {
        return messageRepository.save(Message.builder()
            .conversation(conversation)
            .content("사진")
            .senderType(SenderType.RESIDENT)
            .messageType(MessageType.TEXT)
            .build());
    }

    private File saveFile(String key) {
        return fileRepository.save(File.builder()
            .fileKey(key)
            .fileSize(1024)
            .fileType("jpg")
            .originalName(key + ".jpg")
            .build());
    }

    private void attach(Message message, File file, int seq) {
        messageFileGroupRepository.save(MessageFileGroup.builder()
            .message(message)
            .attachment(file)
            .fileGroupSeq(seq)
            .build());
    }
}
