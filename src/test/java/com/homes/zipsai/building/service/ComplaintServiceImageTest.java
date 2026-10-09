package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.dto.request.ComplaintCreateRequest;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.ComplaintDetailRepository;
import com.homes.zipsai.building.repository.ComplaintRepository;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.ai.AiComplaintState;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.service.ConversationService;
import com.homes.zipsai.global.exception.NotFoundException;
import com.homes.zipsai.user.domain.User;

@ExtendWith(MockitoExtension.class)
class ComplaintServiceImageTest {

    private static final long RESIDENT_ID = 1L;
    private static final long CONVERSATION_ID = 10L;

    @Mock
    ComplaintRepository complaintRepository;

    @Mock
    ComplaintDetailRepository complaintDetailRepository;

    @Mock
    BuildingRepository buildingRepository;

    @Mock
    ResidentRoomService residentRoomService;

    @Mock
    ConversationService conversationService;

    @Mock
    S3StorageService s3StorageService;

    @Mock
    ApplicationEventPublisher events;

    ComplaintService complaintService;

    @BeforeEach
    void setUp() {
        complaintService = new ComplaintService(complaintRepository, complaintDetailRepository,
            buildingRepository, residentRoomService, conversationService, s3StorageService,
            new StorageProperties(null, null, null, 300, 0), events);
        given(residentRoomService.getLivingRoom(RESIDENT_ID)).willReturn(livingRoom(user(RESIDENT_ID)));
    }

    @Test
    @DisplayName("대화의 대표 사진을 민원 대표 사진으로 저장한다")
    void savesConversationRepresentativeImage() {
        givenConversation(AiRoute.COMPLAINT);
        givenComplaintSaved();
        File representative = uploaded(4L);
        given(conversationService.findRepresentativeImage(CONVERSATION_ID)).willReturn(representative);

        complaintService.createComplaint(RESIDENT_ID, createRequest());

        assertThat(savedComplaint().getAttachment()).isEqualTo(representative);
    }

    @Test
    @DisplayName("대화에 사진이 없으면 대표 사진 없이 저장한다")
    void savesComplaintWithoutRepresentativeWhenNoImage() {
        givenConversation(AiRoute.COMPLAINT);
        givenComplaintSaved();
        given(conversationService.findRepresentativeImage(CONVERSATION_ID)).willReturn(null);

        complaintService.createComplaint(RESIDENT_ID, createRequest());

        assertThat(savedComplaint().getAttachment()).isNull();
    }

    @Test
    @DisplayName("질의로 접수하면 입주민이 고른 사진을 대표 사진으로 저장한다")
    void savesImageChosenByResidentForQa() {
        givenConversation(AiRoute.KNOWLEDGE);
        givenComplaintSaved();
        File chosen = uploaded(5L);
        given(conversationService.findImages(CONVERSATION_ID)).willReturn(List.of(uploaded(4L), chosen));

        complaintService.createComplaint(RESIDENT_ID, createRequest(5L));

        assertThat(savedComplaint().getAttachment()).isEqualTo(chosen);
    }

    @Test
    @DisplayName("질의로 접수할 때 대화에 없는 사진을 고르면 민원을 만들지 않는다")
    void rejectsQaRepresentativeNotInConversation() {
        givenConversation(AiRoute.KNOWLEDGE);
        given(conversationService.findImages(CONVERSATION_ID)).willReturn(List.of(uploaded(4L)));
        ComplaintCreateRequest request = createRequest(99L);

        assertThatThrownBy(() -> complaintService.createComplaint(RESIDENT_ID, request))
            .isInstanceOf(NotFoundException.class)
            .hasFieldOrPropertyWithValue("code", "ATTACHMENT_NOT_FOUND");
        then(complaintRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("질의로 접수할 때 대표 사진을 고르지 않으면 첫 사진을 저장한다")
    void savesFirstImageForQaWithoutChoice() {
        givenConversation(AiRoute.KNOWLEDGE);
        givenComplaintSaved();
        File first = uploaded(4L);
        given(conversationService.findRepresentativeImage(CONVERSATION_ID)).willReturn(first);

        complaintService.createComplaint(RESIDENT_ID, createRequest());

        assertThat(savedComplaint().getAttachment()).isEqualTo(first);
    }

    @Test
    @DisplayName("사진 없는 질의는 대표 사진 없이 저장한다")
    void savesQaWithoutRepresentativeWhenNoImage() {
        givenConversation(AiRoute.KNOWLEDGE);
        givenComplaintSaved();
        given(conversationService.findRepresentativeImage(CONVERSATION_ID)).willReturn(null);

        complaintService.createComplaint(RESIDENT_ID, createRequest());

        assertThat(savedComplaint().getAttachment()).isNull();
    }

    @Test
    @DisplayName("일반 민원은 고른 사진 ID를 무시하고 첫 사진을 저장한다")
    void ignoresChosenImageForComplaint() {
        givenConversation(AiRoute.COMPLAINT);
        givenComplaintSaved();
        File first = uploaded(4L);
        given(conversationService.findRepresentativeImage(CONVERSATION_ID)).willReturn(first);

        complaintService.createComplaint(RESIDENT_ID, createRequest(5L));

        assertThat(savedComplaint().getAttachment()).isEqualTo(first);
        then(conversationService).should(never()).findImages(anyLong());
    }

    private void givenConversation(AiRoute route) {
        given(conversationService.getOwnedConversation(RESIDENT_ID, CONVERSATION_ID))
            .willReturn(readyConversation(route));
    }

    private void givenComplaintSaved() {
        given(complaintRepository.save(any(Complaint.class)))
            .willAnswer(invocation -> withId(invocation.getArgument(0), 100L));
        given(complaintDetailRepository.save(any(ComplaintDetail.class)))
            .willAnswer(invocation -> invocation.getArgument(0));
    }

    private Complaint savedComplaint() {
        ArgumentCaptor<Complaint> complaint = ArgumentCaptor.forClass(Complaint.class);
        then(complaintRepository).should().save(complaint.capture());
        return complaint.getValue();
    }

    private static ComplaintCreateRequest createRequest() {
        return ComplaintCreateRequest.builder().conversationId(CONVERSATION_ID).build();
    }

    private static ComplaintCreateRequest createRequest(Long representativeAttachmentId) {
        return ComplaintCreateRequest.builder()
            .conversationId(CONVERSATION_ID)
            .representativeAttachmentId(representativeAttachmentId)
            .build();
    }

    private static Conversation readyConversation(AiRoute route) {
        Conversation conversation = Conversation.builder()
            .user(user(RESIDENT_ID))
            .type(ConversationType.INQUIRY)
            .title("천장에서 물이 새요")
            .build();
        ReflectionTestUtils.setField(conversation, "complaintState", AiComplaintState.READY_TO_CONFIRM);
        ReflectionTestUtils.setField(conversation, "currentRoute", route);
        return withId(conversation, CONVERSATION_ID);
    }

    private static File uploaded(long id) {
        File file = new File("key-" + id, 1024, "jpg", "photo.jpg");
        file.markUploaded(1024, "jpg");
        return withId(file, id);
    }

    private static User user(long id) {
        return withId(new User(id + "@example.com", "password", "입주민", null), id);
    }

    private static Room livingRoom(User resident) {
        Room room = new Room(withId(new Building(user(99L), "서울시 테스트로 1", "테스트빌"), 7L), "302");
        room.invite();
        room.moveIn(resident);
        return room;
    }

    private static <T> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
