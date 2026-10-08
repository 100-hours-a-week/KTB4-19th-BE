package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.domain.ComplaintStatus;
import com.homes.zipsai.building.domain.ComplaintType;
import com.homes.zipsai.building.dto.request.ComplaintCommentUpdateRequest;
import com.homes.zipsai.building.dto.response.ComplaintDetailResponse;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.ComplaintDetailRepository;
import com.homes.zipsai.building.repository.ComplaintRepository;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.service.ConversationService;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.global.exception.NotFoundException;
import com.homes.zipsai.user.domain.User;

@ExtendWith(MockitoExtension.class)
class ComplaintServiceCommentTest {

    private static final long MANAGER_ID = 9L;
    private static final long OTHER_MANAGER_ID = 8L;
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

    @Mock
    ApplicationEventPublisher applicationEventPublisher;

    ComplaintService complaintService;

    @BeforeEach
    void setUp() {
        complaintService = new ComplaintService(complaintRepository, complaintDetailRepository,
            buildingRepository, residentRoomService, conversationService, s3StorageService,
            new StorageProperties(null, null, null, 300, 0), applicationEventPublisher);
    }

    @Test
    @DisplayName("처리완료된 담당 건물 민원에 코멘트를 저장한다")
    void savesCommentOnDoneComplaint() {
        Complaint complaint = complaint(ComplaintType.QA, ComplaintStatus.DONE);
        ComplaintDetail detail = detailOf(complaint);
        givenComplaintWithDetail(complaint, detail);

        complaintService.updateManagerComplaintComment(
            MANAGER_ID, COMPLAINT_ID, new ComplaintCommentUpdateRequest("분리수거장은 지하 1층입니다"));

        assertThat(detail.getComment()).isEqualTo("분리수거장은 지하 1층입니다");
    }

    @Test
    @DisplayName("코멘트를 다시 저장하면 기존 코멘트를 덮어쓴다")
    void replacesExistingComment() {
        Complaint complaint = complaint(ComplaintType.COMPLAINT, ComplaintStatus.DONE);
        ComplaintDetail detail = detailOf(complaint);
        detail.updateComment("업체 연락함");
        givenComplaintWithDetail(complaint, detail);

        complaintService.updateManagerComplaintComment(
            MANAGER_ID, COMPLAINT_ID, new ComplaintCommentUpdateRequest("배관 교체 완료"));

        assertThat(detail.getComment()).isEqualTo("배관 교체 완료");
    }

    @Test
    @DisplayName("처리완료 전인 민원에는 코멘트를 저장할 수 없다")
    void rejectsCommentBeforeDone() {
        Complaint complaint = complaint(ComplaintType.COMPLAINT, ComplaintStatus.IN_PROGRESS);
        given(complaintRepository.findByIdAndDeletedAtIsNull(COMPLAINT_ID)).willReturn(Optional.of(complaint));
        ComplaintCommentUpdateRequest request = new ComplaintCommentUpdateRequest("배관 교체 완료");

        assertThatThrownBy(() -> complaintService.updateManagerComplaintComment(MANAGER_ID, COMPLAINT_ID, request))
            .isInstanceOf(ConflictException.class)
            .hasFieldOrPropertyWithValue("code", "COMPLAINT_NOT_DONE");
    }

    @Test
    @DisplayName("처리완료 전이어도 남아 있는 코멘트는 삭제할 수 있다")
    void deletesCommentBeforeDone() {
        Complaint complaint = complaint(ComplaintType.COMPLAINT, ComplaintStatus.IN_PROGRESS);
        ComplaintDetail detail = detailOf(complaint);
        detail.updateComment("배관 교체 완료");
        givenComplaintWithDetail(complaint, detail);

        complaintService.deleteManagerComplaintComment(MANAGER_ID, COMPLAINT_ID);

        assertThat(detail.getComment()).isNull();
    }

    @Test
    @DisplayName("코멘트를 삭제하면 비워진다")
    void deletesComment() {
        Complaint complaint = complaint(ComplaintType.COMPLAINT, ComplaintStatus.DONE);
        ComplaintDetail detail = detailOf(complaint);
        detail.updateComment("배관 교체 완료");
        givenComplaintWithDetail(complaint, detail);

        complaintService.deleteManagerComplaintComment(MANAGER_ID, COMPLAINT_ID);

        assertThat(detail.getComment()).isNull();
    }

    @Test
    @DisplayName("다른 건물의 민원에는 코멘트를 저장할 수 없다")
    void rejectsCommentOnOtherBuildingComplaint() {
        Complaint complaint = complaint(ComplaintType.COMPLAINT, ComplaintStatus.DONE);
        given(complaintRepository.findByIdAndDeletedAtIsNull(COMPLAINT_ID)).willReturn(Optional.of(complaint));
        ComplaintCommentUpdateRequest request = new ComplaintCommentUpdateRequest("배관 교체 완료");

        assertThatThrownBy(() -> complaintService.updateManagerComplaintComment(
            OTHER_MANAGER_ID, COMPLAINT_ID, request))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("다른 건물의 민원 코멘트는 삭제할 수 없다")
    void rejectsDeletingCommentOnOtherBuildingComplaint() {
        Complaint complaint = complaint(ComplaintType.COMPLAINT, ComplaintStatus.DONE);
        given(complaintRepository.findByIdAndDeletedAtIsNull(COMPLAINT_ID)).willReturn(Optional.of(complaint));

        assertThatThrownBy(() -> complaintService.deleteManagerComplaintComment(OTHER_MANAGER_ID, COMPLAINT_ID))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("없는 민원에는 코멘트를 저장할 수 없다")
    void rejectsCommentOnMissingComplaint() {
        given(complaintRepository.findByIdAndDeletedAtIsNull(COMPLAINT_ID)).willReturn(Optional.empty());
        ComplaintCommentUpdateRequest request = new ComplaintCommentUpdateRequest("배관 교체 완료");

        assertThatThrownBy(() -> complaintService.updateManagerComplaintComment(MANAGER_ID, COMPLAINT_ID, request))
            .isInstanceOf(NotFoundException.class)
            .hasFieldOrPropertyWithValue("code", "COMPLAINT_NOT_FOUND");
    }

    @Test
    @DisplayName("관리자 민원 상세에 민원 유형과 코멘트가 담긴다")
    void includesTypeAndCommentInManagerDetail() {
        Complaint complaint = complaint(ComplaintType.QA, ComplaintStatus.DONE);
        ComplaintDetail detail = detailOf(complaint);
        detail.updateComment("분리수거장은 지하 1층입니다");
        givenComplaintWithDetail(complaint, detail);
        given(conversationService.findImages(complaint.getConversation().getId())).willReturn(List.of());

        ComplaintDetailResponse response = complaintService.getManagerComplaint(MANAGER_ID, COMPLAINT_ID);

        assertThat(response.complaintType()).isEqualTo(ComplaintType.QA);
        assertThat(response.comment()).isEqualTo("분리수거장은 지하 1층입니다");
    }

    private void givenComplaintWithDetail(Complaint complaint, ComplaintDetail detail) {
        given(complaintRepository.findByIdAndDeletedAtIsNull(COMPLAINT_ID)).willReturn(Optional.of(complaint));
        given(complaintDetailRepository.findById(COMPLAINT_ID)).willReturn(Optional.of(detail));
    }

    private static ComplaintDetail detailOf(Complaint complaint) {
        return withId(ComplaintDetail.builder().complaint(complaint).build(), COMPLAINT_ID);
    }

    private static Complaint complaint(ComplaintType type, ComplaintStatus status) {
        User resident = user(1L);
        Building building = withId(new Building(user(MANAGER_ID), "서울시 테스트로 1", "테스트빌"), 7L);
        Conversation conversation = withId(Conversation.builder()
            .user(resident)
            .type(ConversationType.COMPLAINT)
            .title("분리수거 어디서 해요")
            .build(), 10L);
        Complaint complaint = withId(Complaint.builder()
            .conversation(conversation)
            .user(resident)
            .building(building)
            .title("분리수거 어디서 해요")
            .type(type)
            .urgency(0)
            .roomNo("302")
            .build(), COMPLAINT_ID);
        complaint.changeStatus(status);
        return complaint;
    }

    private static User user(long id) {
        return withId(new User(id + "@example.com", "password", "사용자", null), id);
    }

    private static <T> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
