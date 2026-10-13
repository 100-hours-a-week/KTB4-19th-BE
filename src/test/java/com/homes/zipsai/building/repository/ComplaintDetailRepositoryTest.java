package com.homes.zipsai.building.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.domain.ComplaintStatus;
import com.homes.zipsai.building.domain.ComplaintType;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.repository.UserRepository;

@DataJpaTest
class ComplaintDetailRepositoryTest {

    @Autowired
    ComplaintRepository complaintRepository;

    @Autowired
    ComplaintDetailRepository complaintDetailRepository;

    @Autowired
    BuildingRepository buildingRepository;

    @Autowired
    ConversationRepository conversationRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    TestEntityManager entityManager;

    private User manager;

    private User resident;

    private Building building;

    @BeforeEach
    void setUp() {
        manager = userRepository.save(new User("manager@example.com", "password", "관리자", null));
        resident = userRepository.save(new User("resident@example.com", "password", "입주민", null));
        building = buildingRepository.save(Building.builder()
            .manager(manager)
            .roadAddress("서울시 강남구 테헤란로 1")
            .buildingName("집사이 빌딩")
            .build());
    }

    @Test
    @DisplayName("처리완료되고 코멘트가 있으며 아직 전송하지 않은 민원 상세를 조회한다")
    void findsPendingFeedback() {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        entityManager.clear();

        Slice<ComplaintDetail> found = complaintDetailRepository.findPendingAiFeedback(PageRequest.of(0, 10));

        assertThat(found.getContent()).singleElement()
            .satisfies(result -> assertThat(result.getId()).isEqualTo(detail.getId()));
    }

    @Test
    @DisplayName("처리중인 민원은 조회 대상에서 제외한다")
    void excludesNotDoneComplaint() {
        saveComplaintWithDetail(ComplaintStatus.IN_PROGRESS, "확인 중입니다");
        entityManager.clear();

        Slice<ComplaintDetail> found = complaintDetailRepository.findPendingAiFeedback(PageRequest.of(0, 10));

        assertThat(found.getContent()).isEmpty();
    }

    @Test
    @DisplayName("코멘트가 없는 완료 민원은 조회 대상에서 제외한다")
    void excludesComplaintWithoutComment() {
        saveComplaintWithDetail(ComplaintStatus.DONE, null);
        entityManager.clear();

        Slice<ComplaintDetail> found = complaintDetailRepository.findPendingAiFeedback(PageRequest.of(0, 10));

        assertThat(found.getContent()).isEmpty();
    }

    @Test
    @DisplayName("이미 AI로 전송한 민원 상세는 다시 조회하지 않는다")
    void excludesAlreadySentFeedback() {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        detail.markAiFeedbackSent(LocalDateTime.now());
        entityManager.flush();
        entityManager.clear();

        Slice<ComplaintDetail> found = complaintDetailRepository.findPendingAiFeedback(PageRequest.of(0, 10));

        assertThat(found.getContent()).isEmpty();
    }

    private ComplaintDetail saveComplaintWithDetail(ComplaintStatus status, String comment) {
        Conversation conversation = conversationRepository.save(Conversation.builder()
            .user(resident)
            .type(ConversationType.COMPLAINT)
            .title("분리수거 어디서 해요")
            .build());
        Complaint complaint = complaintRepository.save(Complaint.builder()
            .conversation(conversation)
            .user(resident)
            .building(building)
            .title("분리수거 문의")
            .type(ComplaintType.QA)
            .urgency(0)
            .roomNo("302")
            .build());
        complaint.changeStatus(status);
        ComplaintDetail detail = ComplaintDetail.builder()
            .complaint(complaint)
            .location("공용 공간")
            .symptom("분리수거 위치 문의")
            .aiSummary("분리수거장 위치를 물어봄")
            .build();
        entityManager.persist(detail);
        if (comment != null) {
            detail.updateComment(comment);
        }
        return entityManager.persistFlushFind(detail);
    }
}
