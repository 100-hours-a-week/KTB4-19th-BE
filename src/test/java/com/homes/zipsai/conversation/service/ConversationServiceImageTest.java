package com.homes.zipsai.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.service.ResidentRoomService;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.common.repository.FileRepository;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.domain.Message;
import com.homes.zipsai.conversation.domain.MessageFileGroup;
import com.homes.zipsai.conversation.dto.response.AttachmentResponse;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.conversation.repository.MessageFileGroupRepository;
import com.homes.zipsai.conversation.repository.MessageRepository;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.global.exception.NotFoundException;
import com.homes.zipsai.global.exception.ValidationFailedException;
import com.homes.zipsai.user.domain.User;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationServiceImageTest {

    private static final long RESIDENT_ID = 1L;
    private static final long OTHER_USER_ID = 2L;

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
    User resident;

    @BeforeEach
    void setUp() {
        conversationService = new ConversationService(conversationRepository, messageRepository,
            messageFileGroupRepository, fileRepository, residentRoomService, s3StorageService,
            new StorageProperties(null, null, null, 300, 0));
        resident = user(RESIDENT_ID);
        given(residentRoomService.getLivingRoom(RESIDENT_ID)).willReturn(livingRoom(resident));
        given(conversationRepository.save(any(Conversation.class)))
            .willAnswer(invocation -> withId(invocation.getArgument(0), 10L));
        given(messageRepository.save(any(Message.class)))
            .willAnswer(invocation -> withId(invocation.getArgument(0), 20L));
        given(messageFileGroupRepository.save(any(MessageFileGroup.class)))
            .willAnswer(invocation -> invocation.getArgument(0));
        given(s3StorageService.prepareDownload(anyString(), any())).willAnswer(invocation ->
            new S3StorageService.PresignedDownload("https://s3.test/" + invocation.getArgument(0)));
    }

    @Test
    @DisplayName("업로드를 마친 본인 사진은 보낸 순서대로 첨부된다")
    void attachesOwnUploadedImagesInSentOrder() {
        givenFiles(uploaded(4L, resident, "jpg"), uploaded(5L, resident, "png"));

        PendingAiReply reply = conversationService.saveFirstMessage(RESIDENT_ID, "천장에서 물이 새요", List.of(5L, 4L));

        assertThat(reply.residentMessage().attachments()).containsExactly(
            new AttachmentResponse(5L, "https://s3.test/key-5", 1),
            new AttachmentResponse(4L, "https://s3.test/key-4", 2));
        assertThat(reply.aiRequest().message().imageUrls())
            .containsExactly("https://s3.test/key-5", "https://s3.test/key-4");
    }

    @Test
    @DisplayName("같은 사진을 두 번 보내면 한 번만 첨부된다")
    void attachesDuplicateImageOnce() {
        givenFiles(uploaded(4L, resident, "jpg"));

        PendingAiReply reply = conversationService.saveFirstMessage(RESIDENT_ID, "천장에서 물이 새요", List.of(4L, 4L));

        assertThat(reply.residentMessage().attachments()).hasSize(1);
        assertThat(reply.aiRequest().message().imageUrls()).hasSize(1);
    }

    @Test
    @DisplayName("사진 없이 보내면 첨부 없이 저장된다")
    void savesMessageWithoutAttachmentsWhenNoImages() {
        PendingAiReply reply = conversationService.saveFirstMessage(RESIDENT_ID, "천장에서 물이 새요", null);

        assertThat(reply.residentMessage().attachments()).isEmpty();
        assertThat(reply.aiRequest().message().imageUrls()).isEmpty();
    }

    @Test
    @DisplayName("사진만 보낸 첫 메시지는 사진 문의 제목으로 저장되고 AI에 사진만 전달된다")
    void savesImageOnlyFirstMessage() {
        givenFiles(uploaded(4L, resident, "jpg"));

        PendingAiReply reply = conversationService.saveFirstMessage(RESIDENT_ID, "", List.of(4L));

        assertThat(reply.conversation().getTitle()).isEqualTo("사진 문의");
        assertThat(reply.aiRequest().message().text()).isEmpty();
        assertThat(reply.aiRequest().message().imageUrls()).containsExactly("https://s3.test/key-4");
    }

    @Test
    @DisplayName("다른 사람의 사진은 첨부할 수 없다")
    void rejectsImageOwnedByAnotherUser() {
        givenFiles(uploaded(4L, user(OTHER_USER_ID), "jpg"));

        assertThatThrownBy(() -> conversationService.saveFirstMessage(RESIDENT_ID, "천장에서 물이 새요", List.of(4L)))
            .isInstanceOf(ForbiddenException.class);
        then(conversationRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("업로드가 끝나지 않은 사진은 첨부할 수 없다")
    void rejectsImageNotYetUploaded() {
        givenFiles(pending(4L, resident, "jpg"));

        assertThatThrownBy(() -> conversationService.saveFirstMessage(RESIDENT_ID, "천장에서 물이 새요", List.of(4L)))
            .isInstanceOf(ConflictException.class)
            .hasFieldOrPropertyWithValue("code", "UPLOAD_NOT_COMPLETED");
        then(conversationRepository).should(never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pdf", "heic", "gif"})
    @DisplayName("jpg와 png가 아닌 파일은 첨부할 수 없다")
    void rejectsFileThatIsNotJpgOrPng(String fileType) {
        givenFiles(uploaded(4L, resident, fileType));

        assertThatThrownBy(() -> conversationService.saveFirstMessage(RESIDENT_ID, "천장에서 물이 새요", List.of(4L)))
            .isInstanceOf(ValidationFailedException.class)
            .hasFieldOrPropertyWithValue("code", "VALIDATION_FAILED");
        then(conversationRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("없는 파일은 첨부할 수 없다")
    void rejectsMissingFile() {
        givenFiles();

        assertThatThrownBy(() -> conversationService.saveFirstMessage(RESIDENT_ID, "천장에서 물이 새요", List.of(4L)))
            .isInstanceOf(NotFoundException.class)
            .hasFieldOrPropertyWithValue("code", "ATTACHMENT_NOT_FOUND");
    }

    @Test
    @DisplayName("삭제된 파일은 첨부할 수 없다")
    void rejectsDeletedFile() {
        File deleted = uploaded(4L, resident, "jpg");
        ReflectionTestUtils.setField(deleted, "deletedAt", LocalDateTime.now());
        givenFiles(deleted);

        assertThatThrownBy(() -> conversationService.saveFirstMessage(RESIDENT_ID, "천장에서 물이 새요", List.of(4L)))
            .isInstanceOf(NotFoundException.class)
            .hasFieldOrPropertyWithValue("code", "ATTACHMENT_NOT_FOUND");
    }

    @Test
    @DisplayName("후속 메시지에도 다른 사람의 사진은 첨부할 수 없다")
    void rejectsOthersImageInFollowUpMessage() {
        Conversation conversation =
            withId(new Conversation(resident, ConversationType.INQUIRY, "천장에서 물이 새요"), 10L);
        given(conversationRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(conversation));
        givenFiles(uploaded(4L, user(OTHER_USER_ID), "jpg"));

        assertThatThrownBy(() -> conversationService.saveNextMessage(RESIDENT_ID, 10L, "안방이요", List.of(4L)))
            .isInstanceOf(ForbiddenException.class);
        then(messageRepository).should(never()).save(any());
    }

    private void givenFiles(File... files) {
        for (File file : files) {
            given(fileRepository.findById(file.getId())).willReturn(Optional.of(file));
        }
    }

    private static File uploaded(long id, User owner, String fileType) {
        File file = pending(id, owner, fileType);
        file.markUploaded(1024, fileType);
        return file;
    }

    private static File pending(long id, User owner, String fileType) {
        File file = new File("key-" + id, 1024, fileType, "photo." + fileType);
        file.assignOwner(owner);
        return withId(file, id);
    }

    private static User user(long id) {
        return withId(new User(id + "@example.com", "password", "입주민", null), id);
    }

    private static Room livingRoom(User resident) {
        Room room = new Room(new Building(user(99L), "서울시 테스트로 1", "테스트빌"), "302");
        room.invite();
        room.moveIn(resident);
        return room;
    }

    private static <T> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
