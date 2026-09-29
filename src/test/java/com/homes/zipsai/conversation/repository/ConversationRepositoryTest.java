package com.homes.zipsai.conversation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.conversation.ai.AiComplaintDraft;
import com.homes.zipsai.conversation.ai.AiComplaintState;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationStatus;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.repository.UserRepository;

@DataJpaTest
class ConversationRepositoryTest {

    private static final LocalDateTime NOON = LocalDateTime.of(2026, 9, 27, 12, 0);

    @Autowired
    ConversationRepository conversationRepository;

    @Autowired
    UserRepository userRepository;

    private User resident;

    @BeforeEach
    void setUp() {
        resident = userRepository.save(new User("resident@example.com", "password", "입주민", null));
    }

    @Test
    @DisplayName("마지막 메시지가 최근인 대화부터 조회한다")
    void findsRecentlyMessagedConversationsFirst() {
        saveConversation(resident, "분리수거 요일", NOON);
        saveConversation(resident, "택배 보관함", NOON.plusHours(2));
        saveConversation(resident, "주차 등록", NOON.plusHours(1));

        List<Conversation> conversations =
            conversationRepository.findLatestByUserId(resident.getId(), null, Limit.of(20));

        assertThat(conversations).extracting(Conversation::getTitle).containsExactly("택배 보관함", "주차 등록", "분리수거 요일");
    }

    @Test
    @DisplayName("다른 입주민의 대화는 조회하지 않는다")
    void excludesOtherResidentsConversations() {
        User otherResident = userRepository.save(new User("other@example.com", "password", "입주민", null));
        saveConversation(resident, "분리수거 요일", NOON);
        saveConversation(otherResident, "택배 보관함", NOON);

        List<Conversation> conversations =
            conversationRepository.findLatestByUserId(resident.getId(), null, Limit.of(20));

        assertThat(conversations).extracting(Conversation::getTitle).containsExactly("분리수거 요일");
    }

    @Test
    @DisplayName("검색어가 있으면 제목에 검색어가 들어간 대화만 조회한다")
    void findsConversationsWhoseTitleContainsKeyword() {
        saveConversation(resident, "분리수거 요일", NOON);
        saveConversation(resident, "주차 등록 방법", NOON.plusHours(1));

        List<Conversation> conversations =
            conversationRepository.findLatestByUserId(resident.getId(), "주차", Limit.of(20));

        assertThat(conversations).extracting(Conversation::getTitle).containsExactly("주차 등록 방법");
    }

    @Test
    @DisplayName("커서가 있으면 커서보다 먼저 대화한 대화만 조회한다")
    void findsConversationsOlderThanCursor() {
        saveConversation(resident, "분리수거 요일", NOON);
        saveConversation(resident, "주차 등록", NOON.plusHours(1));
        Conversation cursor = saveConversation(resident, "택배 보관함", NOON.plusHours(2));

        List<Conversation> conversations = conversationRepository.findLatestByUserIdBefore(
            resident.getId(), null, cursor.getLastMessageAt(), cursor.getId(), Limit.of(20));

        assertThat(conversations).extracting(Conversation::getTitle).containsExactly("주차 등록", "분리수거 요일");
    }

    @Test
    @DisplayName("마지막 메시지 시각이 같으면 커서보다 ID가 작은 대화만 조회한다")
    void comparesIdWhenLastMessageTimeIsSame() {
        Conversation older = saveConversation(resident, "분리수거 요일", NOON);
        Conversation cursor = saveConversation(resident, "택배 보관함", NOON);

        List<Conversation> conversations = conversationRepository.findLatestByUserIdBefore(
            resident.getId(), null, cursor.getLastMessageAt(), cursor.getId(), Limit.of(20));

        assertThat(conversations).containsExactly(older);
    }

    @Test
    @DisplayName("마지막 메시지 후 5분이 지난 진행 중 대화를 종료한다")
    void closesActiveConversationIdleForFiveMinutes() {
        Conversation conversation = saveConversation(resident, "분리수거 요일", NOON);

        conversationRepository.closeIdleConversations(NOON, NOON.plusMinutes(5));

        assertThat(statusOf(conversation)).isEqualTo(ConversationStatus.CLOSED);
    }

    @Test
    @DisplayName("마지막 메시지 후 5분이 지나지 않은 대화는 종료하지 않는다")
    void keepsRecentConversationActive() {
        Conversation conversation = saveConversation(resident, "분리수거 요일", NOON.plusSeconds(1));

        conversationRepository.closeIdleConversations(NOON, NOON.plusMinutes(5));

        assertThat(statusOf(conversation)).isEqualTo(ConversationStatus.ACTIVE);
    }

    @Test
    @DisplayName("접수 확인을 기다리는 대화는 5분이 지나도 종료하지 않는다")
    void keepsConversationAwaitingConfirmationActive() {
        Conversation conversation = saveConversation(resident, "천장 누수", NOON);
        ReflectionTestUtils.setField(conversation, "complaintState", AiComplaintState.READY_TO_CONFIRM);

        conversationRepository.closeIdleConversations(NOON, NOON.plusMinutes(5));

        assertThat(statusOf(conversation)).isEqualTo(ConversationStatus.ACTIVE);
    }

    @Test
    @DisplayName("민원을 접수한 대화는 5분이 지나도 민원 생성 완료 상태로 둔다")
    void keepsComplaintCreatedConversation() {
        Conversation conversation = saveConversation(resident, "천장 누수", NOON);
        conversation.markComplaintCreated("천장 누수", new AiComplaintDraft("안방", "천장 누수", null));

        conversationRepository.closeIdleConversations(NOON, NOON.plusMinutes(5));

        assertThat(statusOf(conversation)).isEqualTo(ConversationStatus.COMPLAINT_CREATED);
    }

    private ConversationStatus statusOf(Conversation conversation) {
        return conversationRepository.findById(conversation.getId()).orElseThrow().getStatus();
    }

    private Conversation saveConversation(User user, String title, LocalDateTime lastMessageAt) {
        Conversation conversation = Conversation.builder()
            .user(user)
            .type(ConversationType.INQUIRY)
            .title(title)
            .build();
        conversation.updateLastMessageAt(lastMessageAt);
        return conversationRepository.save(conversation);
    }
}
