package com.homes.zipsai.conversation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Limit;

import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.repository.UserRepository;

@DataJpaTest
class MessageRepositoryTest {

    @Autowired
    MessageRepository messageRepository;

    @Autowired
    ConversationRepository conversationRepository;

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
    @DisplayName("최신 메시지부터 개수만큼 조회한다")
    void findsLatestMessagesFirst() {
        saveMessage("첫 번째");
        saveMessage("두 번째");
        saveMessage("세 번째");

        List<Message> messages = messageRepository.findLatestByConversationId(conversation.getId(), Limit.of(2));

        assertThat(messages).extracting(Message::getContent).containsExactly("세 번째", "두 번째");
    }

    @Test
    @DisplayName("커서가 있으면 커서보다 오래된 메시지만 조회한다")
    void findsMessagesOlderThanCursor() {
        saveMessage("첫 번째");
        saveMessage("두 번째");
        Message cursor = saveMessage("세 번째");

        List<Message> messages = messageRepository.findLatestByConversationIdBefore(
            conversation.getId(), cursor.getId(), Limit.of(20));

        assertThat(messages).extracting(Message::getContent).containsExactly("두 번째", "첫 번째");
    }

    private Message saveMessage(String content) {
        return messageRepository.save(Message.builder()
            .conversation(conversation)
            .content(content)
            .senderType(SenderType.RESIDENT)
            .messageType(MessageType.TEXT)
            .build());
    }
}
