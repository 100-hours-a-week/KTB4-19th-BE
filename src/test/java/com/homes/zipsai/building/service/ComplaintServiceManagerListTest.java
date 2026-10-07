package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.dto.response.ComplaintListResponse;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.ComplaintDetailRepository;
import com.homes.zipsai.building.repository.ComplaintRepository;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.service.ConversationService;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.user.domain.User;

@ExtendWith(MockitoExtension.class)
class ComplaintServiceManagerListTest {

    private static final long MANAGER_ID = 9L;

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
    }

    @Test
    @DisplayName("민원이 있으면 담당 건물을 따로 확인하지 않는다")
    void skipsBuildingCheckWhenComplaintsFound() {
        givenManagerComplaints(List.of(complaint()));

        ComplaintListResponse response = complaintService.getManagerComplaints(MANAGER_ID, null, null, false, 0, 20);

        assertThat(response.complaints()).hasSize(1);
        then(buildingRepository).should(never()).existsByManager_IdAndDeletedAtIsNull(anyLong());
    }

    @Test
    @DisplayName("담당 건물이 없는 관리자는 민원 목록을 볼 수 없다")
    void rejectsManagerWithoutBuilding() {
        givenManagerComplaints(List.of());
        given(buildingRepository.existsByManager_IdAndDeletedAtIsNull(MANAGER_ID)).willReturn(false);

        assertThatThrownBy(() -> complaintService.getManagerComplaints(MANAGER_ID, null, null, false, 0, 20))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("담당 건물이 있으면 민원이 없어도 빈 목록을 돌려준다")
    void returnsEmptyListWhenBuildingHasNoComplaints() {
        givenManagerComplaints(List.of());
        given(buildingRepository.existsByManager_IdAndDeletedAtIsNull(MANAGER_ID)).willReturn(true);

        ComplaintListResponse response = complaintService.getManagerComplaints(MANAGER_ID, null, null, false, 0, 20);

        assertThat(response.complaints()).isEmpty();
    }

    private void givenManagerComplaints(List<Complaint> complaints) {
        given(complaintRepository.findManagerComplaints(eq(MANAGER_ID), any(), any(), anyBoolean(), anyInt(), any()))
            .willReturn(new SliceImpl<>(complaints, PageRequest.of(0, 20), false));
    }

    private static Complaint complaint() {
        User manager = withId(new User("manager@example.com", "password", "관리자", null), MANAGER_ID);
        User resident = withId(new User("resident@example.com", "password", "입주민", null), 1L);
        return withId(Complaint.builder()
            .user(resident)
            .building(withId(new Building(manager, "서울시 테스트로 1", "테스트빌"), 7L))
            .title("천장에서 물이 새요")
            .urgency(0)
            .roomNo("302")
            .build(), 100L);
    }

    private static <T> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
