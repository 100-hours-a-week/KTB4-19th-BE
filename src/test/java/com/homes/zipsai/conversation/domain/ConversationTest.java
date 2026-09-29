package com.homes.zipsai.conversation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.homes.zipsai.conversation.ai.AiComplaintDraft;
import com.homes.zipsai.conversation.ai.AiComplaintState;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.global.exception.ConflictException;

class ConversationTest {

    private static final LocalDateTime NOON = LocalDateTime.of(2026, 9, 29, 12, 0);

    @Test
    @DisplayName("근거 없는 질의의 질문 카드는 초안을 질문으로 바꾸고 접수 확인을 기다린다")
    void replacesDraftWithQaCardQuestion() {
        Conversation conversation = conversation();
        conversation.applyAiResponse(complaint("안방", "천장 누수", List.of("occurredAt")));

        conversation.applyAiResponse(qaCard("엘리베이터 정기 점검 일정 문의"));

        assertThat(conversation.currentDraft())
            .isEqualTo(new AiComplaintDraft(null, "엘리베이터 정기 점검 일정 문의", null));
        assertThat(conversation.isReadyToConfirmComplaint()).isTrue();
    }

    @Test
    @DisplayName("민원 초안 변경분은 온 값만 덮어쓰고 나머지는 유지한다")
    void keepsPreviousDraftValuesNotInPatch() {
        Conversation conversation = conversation();
        conversation.applyAiResponse(complaint("안방", null, List.of("symptom", "occurredAt")));

        conversation.applyAiResponse(complaint(null, "천장 누수", List.of("occurredAt")));

        assertThat(conversation.currentDraft()).isEqualTo(new AiComplaintDraft("안방", "천장 누수", null));
        assertThat(conversation.getComplaintState()).isEqualTo(AiComplaintState.COLLECTING);
    }

    @Test
    @DisplayName("접수 확인을 기다리는 대화에는 메시지를 보낼 수 없다")
    void rejectsMessageWhileAwaitingConfirmation() {
        Conversation conversation = conversation();
        conversation.applyAiResponse(complaint("안방", "천장 누수", List.of()));

        assertThatThrownBy(() -> conversation.verifyCanSendMessage(NOON))
            .isInstanceOf(ConflictException.class)
            .hasFieldOrPropertyWithValue("code", "CONVERSATION_AWAITING_CONFIRMATION");
    }

    @Test
    @DisplayName("민원이 접수된 대화는 늦게 온 AI 응답으로 상태가 바뀌지 않는다")
    void ignoresAiResponseAfterComplaintCreated() {
        Conversation conversation = conversation();
        conversation.markComplaintCreated("천장 누수", new AiComplaintDraft("안방", "천장 누수", null));

        conversation.applyAiResponse(complaint("거실", "벽 곰팡이", List.of()));

        assertThat(conversation.currentDraft()).isEqualTo(new AiComplaintDraft("안방", "천장 누수", null));
        assertThat(conversation.getComplaintState()).isNull();
        assertThat(conversation.getCurrentRoute()).isNull();
    }

    @Test
    @DisplayName("요약 카드가 뜨기 전에는 민원을 접수할 수 없다")
    void rejectsComplaintBeforeSummaryCard() {
        Conversation conversation = conversation();
        conversation.applyAiResponse(complaint("안방", null, List.of("symptom")));

        assertThatThrownBy(conversation::verifyCanCreateComplaint)
            .isInstanceOf(ConflictException.class)
            .hasFieldOrPropertyWithValue("code", "COMPLAINT_NOT_READY");
    }

    @Test
    @DisplayName("이미 민원을 접수한 대화는 다시 접수할 수 없다")
    void rejectsSecondComplaint() {
        Conversation conversation = conversation();
        conversation.markComplaintCreated("천장 누수", new AiComplaintDraft("안방", "천장 누수", null));

        assertThatThrownBy(conversation::verifyCanCreateComplaint)
            .isInstanceOf(ConflictException.class)
            .hasFieldOrPropertyWithValue("code", "COMPLAINT_ALREADY_CREATED");
    }

    @Test
    @DisplayName("민원을 접수한 대화에는 메시지를 보낼 수 없다")
    void rejectsMessageAfterComplaintCreated() {
        Conversation conversation = conversation();
        conversation.markComplaintCreated("천장 누수", new AiComplaintDraft("안방", "천장 누수", null));

        assertThatThrownBy(() -> conversation.verifyCanSendMessage(NOON))
            .isInstanceOf(ConflictException.class)
            .hasFieldOrPropertyWithValue("code", "CONVERSATION_CLOSED");
    }

    @Test
    @DisplayName("마지막 메시지 후 5분이 지나기 전에는 진행 중이다")
    void staysActiveBeforeIdleTimeToClose() {
        Conversation conversation = conversationLastMessagedAt(NOON);

        assertThat(conversation.statusAt(NOON.plusMinutes(5).minusSeconds(1))).isEqualTo(ConversationStatus.ACTIVE);
    }

    @Test
    @DisplayName("마지막 메시지 후 5분이 지나면 대화가 종료된다")
    void closesAfterIdleTimeToClose() {
        Conversation conversation = conversationLastMessagedAt(NOON);

        assertThat(conversation.closesAt().toLocalDateTime()).isEqualTo(NOON.plusMinutes(5));
        assertThat(conversation.statusAt(NOON.plusMinutes(5))).isEqualTo(ConversationStatus.CLOSED);
    }

    @Test
    @DisplayName("종료된 대화에는 메시지를 보낼 수 없다")
    void rejectsMessageAfterIdleTimeToClose() {
        Conversation conversation = conversationLastMessagedAt(NOON);

        assertThatThrownBy(() -> conversation.verifyCanSendMessage(NOON.plusMinutes(5)))
            .isInstanceOf(ConflictException.class)
            .hasFieldOrPropertyWithValue("code", "CONVERSATION_CLOSED");
    }

    @Test
    @DisplayName("접수 확인을 기다리는 대화는 5분이 지나도 종료되지 않는다")
    void keepsConversationAwaitingConfirmationOpen() {
        Conversation conversation = conversationLastMessagedAt(NOON);
        conversation.applyAiResponse(complaint("안방", "천장 누수", List.of()));

        assertThat(conversation.closesAt()).isNull();
        assertThat(conversation.statusAt(NOON.plusHours(1))).isEqualTo(ConversationStatus.ACTIVE);
    }

    @Test
    @DisplayName("민원을 접수한 대화는 5분이 지나도 민원 생성 완료 상태다")
    void keepsComplaintCreatedStatusAfterIdleTime() {
        Conversation conversation = conversationLastMessagedAt(NOON);
        conversation.markComplaintCreated("천장 누수", new AiComplaintDraft("안방", "천장 누수", null));

        assertThat(conversation.closesAt()).isNull();
        assertThat(conversation.statusAt(NOON.plusHours(1))).isEqualTo(ConversationStatus.COMPLAINT_CREATED);
    }

    private static Conversation conversationLastMessagedAt(LocalDateTime lastMessageAt) {
        Conversation conversation = conversation();
        conversation.updateLastMessageAt(lastMessageAt);
        return conversation;
    }

    private static Conversation conversation() {
        return Conversation.builder()
            .type(ConversationType.INQUIRY)
            .title("천장에서 물이 새요")
            .build();
    }

    private static AiConverseResponse complaint(String location, String symptom, List<String> missingFields) {
        AiConverseResponse.DraftPatch patch = new AiConverseResponse.DraftPatch(location, symptom, null);
        return response(AiRoute.COMPLAINT, AiComplaintState.COLLECTING,
            new AiConverseResponse.Result(patch, null, missingFields, List.of()));
    }

    private static AiConverseResponse qaCard(String question) {
        return response(AiRoute.KNOWLEDGE, null, new AiConverseResponse.Result(
            null, new AiConverseResponse.QaCardDraft(question), List.of(), List.of()));
    }

    private static AiConverseResponse response(AiRoute route, AiComplaintState nextComplaintState,
                                               AiConverseResponse.Result result) {
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, "trace-1",
            new AiConverseResponse.Data(route, nextComplaintState, "답변", result));
    }
}
