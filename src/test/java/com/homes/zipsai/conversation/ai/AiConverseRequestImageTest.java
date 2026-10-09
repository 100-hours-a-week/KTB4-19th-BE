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
    @DisplayName("지금 보낸 메시지의 사진은 ID와 URL로 message에 담긴다")
    void putsCurrentMessageImagesInMessage() {
        List<AiConverseRequest.MessageImage> images =
            List.of(new AiConverseRequest.MessageImage(31L, "https://s3.test/current.jpg"));

        AiConverseRequest request =
            AiConverseRequest.of(room, conversation, currentMessage, images, List.of(), "trace");

        assertThat(request.message().images()).containsExactlyElementsOf(images);
    }

    @Test
    @DisplayName("이전 입주민 메시지의 사진은 ID와 분석 결과로 이력에 담긴다")
    void putsImageAnalysisInResidentHistory() {
        AiConverseRequest.HistoryImage image = new AiConverseRequest.HistoryImage(31L, "천장 얼룩", "관리실 010");

        AiConverseRequest.HistoryMessage history = AiConverseRequest.HistoryMessage.of(firstMessage, List.of(image));

        assertThat(history.images()).containsExactly(image);
    }

    @Test
    @DisplayName("사진이 없는 입주민 이력은 빈 사진 목록으로 담긴다")
    void putsEmptyImagesInResidentHistoryWithoutImages() {
        AiConverseRequest.HistoryMessage history = AiConverseRequest.HistoryMessage.of(firstMessage, List.of());

        assertThat(history.images()).isEmpty();
    }

    @Test
    @DisplayName("AI 답변 이력에는 사진 필드를 담지 않는다")
    void omitsImagesInAssistantHistory() {
        AiConverseRequest.HistoryMessage history = AiConverseRequest.HistoryMessage.of(reply, List.of());

        assertThat(history.images()).isNull();
    }

    @Test
    @DisplayName("민원 초안에는 저장된 민원 유형과 사진 ID가 담긴다")
    void putsStoredIssueTypeAndAttachmentIdsInComplaintDraft() {
        conversation.applyAiResponse(collectingWithImages());

        AiConverseRequest request =
            AiConverseRequest.of(room, conversation, currentMessage, List.of(), List.of(), "trace");

        assertThat(request.complaintDraft().issueType()).isEqualTo("leak");
        assertThat(request.complaintDraft().attachmentIds()).containsExactly(31L, 32L);
        assertThat(request.complaintDraft().symptom()).isEqualTo("천장 누수");
    }

    @Test
    @DisplayName("민원 초안이 없으면 초안은 보내지 않는다")
    void omitsComplaintDraftWithoutDraft() {
        AiConverseRequest request =
            AiConverseRequest.of(room, conversation, currentMessage, List.of(), List.of(), "trace");

        assertThat(request.complaintDraft()).isNull();
    }

    private static AiConverseResponse collectingWithImages() {
        AiConverseResponse.DraftPatch patch = AiConverseResponse.DraftPatch.builder()
            .symptom("천장 누수")
            .issueType("leak")
            .attachmentIds(List.of(31L, 32L))
            .build();
        return new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, "trace",
            new AiConverseResponse.Data(AiRoute.COMPLAINT, AiComplaintState.COLLECTING, "위치가 어디인가요?",
                AiConverseResponse.Result.builder().complaintDraft(patch).missingFields(List.of("location")).build()));
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
