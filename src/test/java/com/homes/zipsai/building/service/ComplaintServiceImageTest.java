package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.service.ConversationService;
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

    ComplaintService complaintService;

    @BeforeEach
    void setUp() {
        complaintService = new ComplaintService(complaintRepository, complaintDetailRepository,
            buildingRepository, residentRoomService, conversationService, s3StorageService,
            new StorageProperties(null, null, null, 300, 0));
        given(conversationService.getOwnedConversation(RESIDENT_ID, CONVERSATION_ID))
            .willReturn(readyConversation());
        given(residentRoomService.getLivingRoom(RESIDENT_ID)).willReturn(livingRoom(user(RESIDENT_ID)));
        given(complaintRepository.save(any(Complaint.class)))
            .willAnswer(invocation -> withId(invocation.getArgument(0), 100L));
        given(complaintDetailRepository.save(any(ComplaintDetail.class)))
            .willAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("대화의 대표 사진을 민원 대표 사진으로 저장한다")
    void savesConversationRepresentativeImage() {
        File representative = uploaded(4L);
        given(conversationService.findRepresentativeImage(CONVERSATION_ID)).willReturn(representative);

        complaintService.createComplaint(RESIDENT_ID, createRequest());

        assertThat(savedComplaint().getAttachment()).isEqualTo(representative);
    }

    @Test
    @DisplayName("대화에 사진이 없으면 대표 사진 없이 저장한다")
    void savesComplaintWithoutRepresentativeWhenNoImage() {
        given(conversationService.findRepresentativeImage(CONVERSATION_ID)).willReturn(null);

        complaintService.createComplaint(RESIDENT_ID, createRequest());

        assertThat(savedComplaint().getAttachment()).isNull();
    }

    private Complaint savedComplaint() {
        ArgumentCaptor<Complaint> complaint = ArgumentCaptor.forClass(Complaint.class);
        then(complaintRepository).should().save(complaint.capture());
        return complaint.getValue();
    }

    private static ComplaintCreateRequest createRequest() {
        return new ComplaintCreateRequest(CONVERSATION_ID, null, null, null);
    }

    private static Conversation readyConversation() {
        Conversation conversation = Conversation.builder()
            .user(user(RESIDENT_ID))
            .type(ConversationType.INQUIRY)
            .title("천장에서 물이 새요")
            .build();
        ReflectionTestUtils.setField(conversation, "complaintState", AiComplaintState.READY_TO_CONFIRM);
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
