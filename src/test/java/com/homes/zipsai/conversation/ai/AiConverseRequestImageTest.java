package com.homes.zipsai.conversation.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.MessageType;
import com.homes.zipsai.conversation.domain.SenderType;
import com.homes.zipsai.user.domain.User;

class AiConverseRequestImageTest {

    private final User resident = withId(new User("resident@example.com", "password", "입주민", null), 1L);
    private final Room room = livingRoom(resident);
    private final Conversation conversation =
        withId(new Conversation(resident, ConversationType.INQUIRY, "천장에서 물이 새요"), 10L);
    private final Message firstMessage = message(21L, SenderType.RESIDENT, "천장에서 물이 새요");
    private final Message reply = message(22L, SenderType.ASSISTANT, "위치가 어디인가요?");
    private final Message currentMessage = message(23L, SenderType.RESIDENT, "안방이요");

    @Test
    @DisplayName("지금 보낸 메시지의 사진은 message에 담긴다")
    void putsCurrentMessageImagesInMessage() {
        AiConverseRequest request = AiConverseRequest.of(room, conversation, currentMessage,
            List.of("https://s3.test/current.jpg"), List.of());

        assertThat(request.message().imageUrls()).containsExactly("https://s3.test/current.jpg");
    }

    @Test
    @DisplayName("이전 메시지의 사진은 각 이력 메시지에 담긴다")
    void putsPreviousImagesInEachHistoryMessage() {
        List<AiConverseRequest.HistoryMessage> history = List.of(
            AiConverseRequest.HistoryMessage.of(firstMessage, List.of("https://s3.test/first.jpg")),
            AiConverseRequest.HistoryMessage.of(reply, List.of()));

        AiConverseRequest request = AiConverseRequest.of(room, conversation, currentMessage, List.of(), history);

        assertThat(request.conversationHistory().getFirst().imageUrls())
            .containsExactly("https://s3.test/first.jpg");
        assertThat(request.conversationHistory().getLast().imageUrls()).isEmpty();
        assertThat(request.message().imageUrls()).isEmpty();
    }

    @Test
    @DisplayName("민원 초안이 있으면 대화의 모든 사진이 초안에 담긴다")
    void putsEveryConversationImageInComplaintDraft() {
        conversation.applyAiResponse(collectingWithSymptom());
        List<AiConverseRequest.HistoryMessage> history = List.of(
            AiConverseRequest.HistoryMessage.of(firstMessage, List.of("https://s3.test/first.jpg")),
            AiConverseRequest.HistoryMessage.of(reply, List.of()));

        AiConverseRequest request = AiConverseRequest.of(room, conversation, currentMessage,
            List.of("https://s3.test/current.jpg"), history);

        assertThat(request.complaintDraft().imageUrls())
            .containsExactly("https://s3.test/first.jpg", "https://s3.test/current.jpg");
    }

    @Test
    @DisplayName("민원 초안이 없으면 사진이 있어도 초안은 보내지 않는다")
    void omitsComplaintDraftWithoutDraft() {
        AiConverseRequest request = AiConverseRequest.of(room, conversation, currentMessage,
            List.of("https://s3.test/current.jpg"), List.of());

        assertThat(request.complaintDraft()).isNull();
    }

    private static AiConverseResponse collectingWithSymptom() {
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, "trace",
            new AiConverseResponse.Data(AiRoute.COMPLAINT, AiComplaintState.COLLECTING, "위치가 어디인가요?",
                new AiConverseResponse.Result(new AiConverseResponse.DraftPatch(null, "천장 누수", null), null,
                    List.of("location"), List.of())));
    }

    private Message message(long id, SenderType senderType, String content) {
        return withId(new Message(conversation, content, senderType, MessageType.TEXT, "trace"), id);
    }

    private static Room livingRoom(User resident) {
        User manager = withId(new User("manager@example.com", "password", "관리자", null), 99L);
        Room room = new Room(new Building(manager, "서울시 테스트로 1", "테스트빌"), "302");
        room.invite();
        room.moveIn(resident);
        return room;
    }

    private static <T> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
