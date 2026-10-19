package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.dto.response.ComplaintDetailResponse;
import com.homes.zipsai.building.dto.response.ComplaintListResponse;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.ComplaintDetailRepository;
import com.homes.zipsai.building.repository.ComplaintRepository;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.service.ConversationService;
import com.homes.zipsai.user.domain.User;

@ExtendWith(MockitoExtension.class)
class ComplaintServiceUrgencyTest {

    private static final long MANAGER_ID = 9L;
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
    ApplicationEventPublisher events;

    ComplaintService complaintService;

    @BeforeEach
    void setUp() {
        complaintService = new ComplaintService(complaintRepository, complaintDetailRepository,
            buildingRepository, residentRoomService, conversationService, s3StorageService,
            new StorageProperties(null, null, null, 300, 0), events);
    }

    @Test
    @DisplayName("관리자 목록은 AI 긴급 판정과 점수를 그대로 내려준다")
    void listsAiUrgentJudgement() {
        givenManagerComplaints(complaint(80, true));

        ComplaintListResponse response = complaintService.getManagerComplaints(MANAGER_ID, null, null, false, 0, 20);

        assertThat(response.complaints()).singleElement()
            .satisfies(item -> {
                assertThat(item.urgency()).isEqualTo(80);
                assertThat(item.isUrgent()).isTrue();
            });
    }

    @Test
    @DisplayName("AI가 긴급도를 매기지 않은 민원은 목록에서 긴급 여부를 비워 내려준다")
    void listsEmptyUrgencyWhenNotScored() {
        givenManagerComplaints(complaint(null, null));

        ComplaintListResponse response = complaintService.getManagerComplaints(MANAGER_ID, null, null, false, 0, 20);

        assertThat(response.complaints()).singleElement()
            .satisfies(item -> {
                assertThat(item.urgency()).isNull();
                assertThat(item.isUrgent()).isNull();
            });
    }

    @Test
    @DisplayName("관리자 상세는 AI가 긴급하지 않다고 판정한 민원을 긴급하지 않다고 내려준다")
    void showsNotUrgentJudgementInDetail() {
        Complaint complaint = complaint(30, false);
        given(complaintRepository.findByIdAndDeletedAtIsNull(COMPLAINT_ID)).willReturn(Optional.of(complaint));
        given(complaintDetailRepository.findById(COMPLAINT_ID))
            .willReturn(Optional.of(ComplaintDetail.builder().complaint(complaint).build()));
        given(conversationService.findImages(complaint.getConversation().getId())).willReturn(List.of());

        ComplaintDetailResponse response = complaintService.getManagerComplaint(MANAGER_ID, COMPLAINT_ID);

        assertThat(response.urgency()).isEqualTo(30);
        assertThat(response.isUrgent()).isFalse();
    }

    private void givenManagerComplaints(Complaint complaint) {
        given(complaintRepository.findManagerComplaints(eq(MANAGER_ID), any(), any(), anyBoolean(), any()))
            .willReturn(new SliceImpl<>(List.of(complaint), PageRequest.of(0, 20), false));
    }

    private static Complaint complaint(Integer urgency, Boolean urgent) {
        User resident = user(1L);
        Conversation conversation = withId(Conversation.builder()
            .user(resident)
            .type(ConversationType.COMPLAINT)
            .title("세탁기 밑으로 물이 새요")
            .build(), 10L);
        return withId(Complaint.builder()
            .conversation(conversation)
            .user(resident)
            .building(withId(new Building(user(MANAGER_ID), "서울시 테스트로 1", "테스트빌"), 7L))
            .title("세탁기 밑 누수")
            .urgency(urgency)
            .urgent(urgent)
            .urgencyVersion("urgency-v1")
            .roomNo("302")
            .build(), COMPLAINT_ID);
    }

    private static User user(long id) {
        return withId(new User(id + "@example.com", "password", "사용자", null), id);
    }

    private static <T> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
