package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.dto.response.ComplaintDetailResponse;
import com.homes.zipsai.building.dto.response.ComplaintListResponse;
import com.homes.zipsai.building.dto.response.ResidentComplaintListResponse;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.ComplaintDetailRepository;
import com.homes.zipsai.building.repository.ComplaintRepository;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.service.ConversationService;
import com.homes.zipsai.user.domain.User;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComplaintPhotoUrlTest {

    private static final long MANAGER_ID = 9L;
    private static final long RESIDENT_ID = 1L;
    private static final long COMPLAINT_ID = 100L;

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
    User resident;
    Building building;

    @BeforeEach
    void setUp() {
        complaintService = new ComplaintService(complaintRepository, complaintDetailRepository,
            buildingRepository, residentRoomService, conversationService, s3StorageService,
            new StorageProperties(null, null, null, 300, 0));
        resident = user(RESIDENT_ID);
        building = withId(new Building(user(MANAGER_ID), "서울시 테스트로 1", "테스트빌"), 7L);
        given(buildingRepository.findByManager_IdAndDeletedAtIsNull(MANAGER_ID))
            .willReturn(Optional.of(building));
        given(residentRoomService.getLivingRoom(RESIDENT_ID)).willReturn(livingRoom(resident));
        given(s3StorageService.prepareDownload(anyString(), any())).willAnswer(invocation ->
            new S3StorageService.PresignedDownload("https://s3.test/" + invocation.getArgument(0)));
    }

    @Test
    @DisplayName("관리자 목록의 대표 사진은 내려받을 수 있는 URL로 내려간다")
    void putsDownloadUrlOnManagerListItem() {
        givenManagerComplaints(complaint(uploaded(4L)));

        ComplaintListResponse response = complaintService.getManagerComplaints(
            MANAGER_ID, null, null, false, 0, 20);

        assertThat(response.complaints().getFirst().fileUrl()).isEqualTo("https://s3.test/key-4");
    }

    @Test
    @DisplayName("입주민 목록의 대표 사진은 내려받을 수 있는 URL로 내려간다")
    void putsDownloadUrlOnResidentListItem() {
        givenResidentComplaints(complaint(uploaded(4L)));

        ResidentComplaintListResponse response = complaintService.getResidentComplaints(
            RESIDENT_ID, null, null, 0, 20);

        assertThat(response.complaints().getFirst().fileUrl()).isEqualTo("https://s3.test/key-4");
    }

    @Test
    @DisplayName("대표 사진이 없으면 URL은 비어 있다")
    void leavesUrlEmptyWhenNoRepresentative() {
        givenManagerComplaints(complaint(null));

        ComplaintListResponse response = complaintService.getManagerComplaints(
            MANAGER_ID, null, null, false, 0, 20);

        assertThat(response.complaints().getFirst().fileUrl()).isNull();
    }

    @Test
    @DisplayName("대표 사진이 없으면 발급을 요청하지 않는다")
    void doesNotPresignWhenNoRepresentative() {
        givenManagerComplaints(complaint(null));

        complaintService.getManagerComplaints(MANAGER_ID, null, null, false, 0, 20);

        then(s3StorageService).should(never()).prepareDownload(anyString(), any());
    }

    @Test
    @DisplayName("관리자 상세는 대화 사진을 내려받을 수 있는 URL로 내려준다")
    void putsDownloadUrlOnManagerDetailAttachment() {
        Complaint complaint = complaint(uploaded(4L));
        given(conversationService.findImages(any())).willReturn(List.of(uploaded(4L)));
        given(complaintRepository.findByIdAndDeletedAtIsNull(COMPLAINT_ID)).willReturn(Optional.of(complaint));
        given(complaintDetailRepository.findById(COMPLAINT_ID))
            .willReturn(Optional.of(ComplaintDetail.builder().complaint(complaint).build()));

        ComplaintDetailResponse response = complaintService.getManagerComplaint(MANAGER_ID, COMPLAINT_ID);

        assertThat(response.attachments().getFirst().fileUrl()).isEqualTo("https://s3.test/key-4");
    }

    @Test
    @DisplayName("상세는 대화에 올린 사진을 모두 올린 순서대로 내려준다")
    void returnsAllConversationPhotosInOrder() {
        givenManagerDetail(List.of(uploaded(4L), uploaded(5L), uploaded(6L), uploaded(7L)));

        ComplaintDetailResponse response = complaintService.getManagerComplaint(MANAGER_ID, COMPLAINT_ID);

        assertThat(response.attachments()).extracting(ComplaintDetailResponse.AttachmentItem::seq)
            .containsExactly(1, 2, 3, 4);
    }

    @Test
    @DisplayName("상세의 사진 개수는 내려준 사진 수와 같다")
    void reportsTotalPhotoCountOnDetail() {
        givenManagerDetail(List.of(uploaded(4L), uploaded(5L), uploaded(6L), uploaded(7L)));

        ComplaintDetailResponse response = complaintService.getManagerComplaint(MANAGER_ID, COMPLAINT_ID);

        assertThat(response.attachmentCount()).isEqualTo(4);
    }

    private void givenManagerDetail(List<File> images) {
        Complaint complaint = complaint(images.isEmpty() ? null : images.getFirst());
        given(complaintRepository.findByIdAndDeletedAtIsNull(COMPLAINT_ID)).willReturn(Optional.of(complaint));
        given(complaintDetailRepository.findById(COMPLAINT_ID))
            .willReturn(Optional.of(ComplaintDetail.builder().complaint(complaint).build()));
        given(conversationService.findImages(any())).willReturn(images);
    }

    private void givenManagerComplaints(Complaint complaint) {
        given(complaintRepository.findManagerComplaints(any(), any(), any(), anyBoolean(), anyInt(), any()))
            .willReturn(page(complaint));
    }

    private void givenResidentComplaints(Complaint complaint) {
        given(complaintRepository.findResidentComplaints(any(), any(), any(), any()))
            .willReturn(page(complaint));
    }

    private static Page<Complaint> page(Complaint complaint) {
        return new PageImpl<>(List.of(complaint), Pageable.ofSize(20), 1);
    }

    private Complaint complaint(File attachment) {
        return withId(Complaint.builder()
            .conversation(conversation())
            .user(resident)
            .building(building)
            .attachment(attachment)
            .title("천장에서 물이 새요")
            .urgency(0)
            .roomNo("302")
            .build(), COMPLAINT_ID);
    }

    private Conversation conversation() {
        return withId(Conversation.builder()
            .user(resident)
            .type(ConversationType.COMPLAINT)
            .title("천장에서 물이 새요")
            .build(), 10L);
    }

    private static File uploaded(long id) {
        File file = new File("key-" + id, 1024, "jpg", "photo.jpg");
        file.markUploaded(1024, "jpg");
        return withId(file, id);
    }

    private static User user(long id) {
        return withId(new User(id + "@example.com", "password", "사용자", null), id);
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
