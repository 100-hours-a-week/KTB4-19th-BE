package com.homes.zipsai.building.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

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
import com.homes.zipsai.building.domain.ComplaintContent;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.domain.ComplaintStatus;
import com.homes.zipsai.building.domain.ComplaintType;
import com.homes.zipsai.conversation.ai.AiComplaintDraft;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.repository.UserRepository;

@DataJpaTest
class ComplaintDetailRepositoryTest {

    private static final String UNKNOWN = ComplaintContent.from(AiComplaintDraft.EMPTY).symptom();

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

        Slice<ComplaintDetail> found = complaintDetailRepository.findPendingAiFeedback(UNKNOWN, PageRequest.of(0, 10));

        assertThat(found.getContent()).singleElement()
            .satisfies(result -> assertThat(result.getId()).isEqualTo(detail.getId()));
    }

    @Test
    @DisplayName("처리중인 민원은 조회 대상에서 제외한다")
    void excludesNotDoneComplaint() {
        saveComplaintWithDetail(ComplaintStatus.IN_PROGRESS, "확인 중입니다");
        entityManager.clear();

        Slice<ComplaintDetail> found = complaintDetailRepository.findPendingAiFeedback(UNKNOWN, PageRequest.of(0, 10));

        assertThat(found.getContent()).isEmpty();
    }

    @Test
    @DisplayName("코멘트가 없는 완료 민원은 조회 대상에서 제외한다")
    void excludesComplaintWithoutComment() {
        saveComplaintWithDetail(ComplaintStatus.DONE, null);
        entityManager.clear();

        Slice<ComplaintDetail> found = complaintDetailRepository.findPendingAiFeedback(UNKNOWN, PageRequest.of(0, 10));

        assertThat(found.getContent()).isEmpty();
    }

    @Test
    @DisplayName("이미 AI로 전송한 민원 상세는 다시 조회하지 않는다")
    void excludesAlreadySentFeedback() {
        sentFeedback("분리수거장은 지하 1층입니다");

        Slice<ComplaintDetail> found = complaintDetailRepository.findPendingAiFeedback(UNKNOWN, PageRequest.of(0, 10));

        assertThat(found.getContent()).isEmpty();
    }

    @Test
    @DisplayName("민원 유형이 없는 민원은 AI가 거절하므로 조회 대상에서 제외한다")
    void excludesComplaintWithoutType() {
        saveComplaintWithDetail(null, "분리수거 위치 문의", ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        entityManager.clear();

        Slice<ComplaintDetail> found = complaintDetailRepository.findPendingAiFeedback(UNKNOWN, PageRequest.of(0, 10));

        assertThat(found.getContent()).isEmpty();
    }

    @Test
    @DisplayName("증상을 알 수 없는 민원은 조회 대상에서 제외한다")
    void excludesUnknownSymptom() {
        saveComplaintWithDetail(ComplaintType.COMPLAINT, UNKNOWN, ComplaintStatus.DONE, "확인 후 조치했습니다");
        entityManager.clear();

        Slice<ComplaintDetail> found = complaintDetailRepository.findPendingAiFeedback(UNKNOWN, PageRequest.of(0, 10));

        assertThat(found.getContent()).isEmpty();
    }

    @Test
    @DisplayName("전송 후 답변을 수정하면 다시 전송 대상이 된다")
    void includesFeedbackEditedAfterSent() {
        ComplaintDetail detail = sentFeedback("분리수거장은 지하 1층입니다");
        reload(detail).updateComment("분리수거장은 지하 2층으로 옮겼습니다");
        entityManager.flush();
        entityManager.clear();

        Slice<ComplaintDetail> found = complaintDetailRepository.findPendingAiFeedback(UNKNOWN, PageRequest.of(0, 10));

        assertThat(found.getContent()).extracting(ComplaintDetail::getId).containsExactly(detail.getId());
    }

    @Test
    @DisplayName("전송 중 답변이 수정되면 수정본은 유지되고 다시 전송 대상으로 남는다")
    void keepsEditedCommentWhenEditedDuringSend() {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        LocalDateTime readVersion = reload(detail).getUpdatedAt();
        reload(detail).updateComment("분리수거장은 지하 2층으로 옮겼습니다");
        entityManager.flush();

        complaintDetailRepository.markAiFeedbackSent(detail.getId(), readVersion);

        assertThat(reload(detail).getComment()).isEqualTo("분리수거장은 지하 2층으로 옮겼습니다");
        assertThat(complaintDetailRepository.findPendingAiFeedback(UNKNOWN, PageRequest.of(0, 10)).getContent())
            .extracting(ComplaintDetail::getId).containsExactly(detail.getId());
    }

    @Test
    @DisplayName("전송 중 답변이 삭제되면 삭제 상태는 유지되고 AI 삭제 대상이 된다")
    void findsDeletionTargetWhenDeletedDuringSend() {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        LocalDateTime readVersion = reload(detail).getUpdatedAt();
        reload(detail).deleteComment();
        entityManager.flush();

        complaintDetailRepository.markAiFeedbackSent(detail.getId(), readVersion);

        assertThat(reload(detail).getComment()).isNull();
        assertThat(complaintDetailRepository.findPendingAiFeedbackDeletions(PageRequest.of(0, 10)))
            .extracting(AiFeedbackDeletionTarget::complaintDetailId).containsExactly(detail.getId());
    }

    @Test
    @DisplayName("전송한 답변을 삭제하면 AI 삭제 대상이 된다")
    void findsDeletionTargetWhenSentCommentDeleted() {
        ComplaintDetail detail = sentFeedback("분리수거장은 지하 1층입니다");
        reload(detail).deleteComment();
        entityManager.flush();
        entityManager.clear();

        List<AiFeedbackDeletionTarget> targets =
            complaintDetailRepository.findPendingAiFeedbackDeletions(PageRequest.of(0, 10));

        assertThat(targets).singleElement().satisfies(target -> {
            assertThat(target.complaintDetailId()).isEqualTo(detail.getId());
            assertThat(target.buildingId()).isEqualTo(building.getId());
        });
    }

    @Test
    @DisplayName("전송한 적 없는 답변을 삭제하면 AI 삭제 대상이 아니다")
    void excludesNeverSentCommentFromDeletion() {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        reload(detail).deleteComment();
        entityManager.flush();
        entityManager.clear();

        assertThat(complaintDetailRepository.findPendingAiFeedbackDeletions(PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    @DisplayName("AI 삭제 요청 중 다시 답변을 달았으면 삭제 완료를 기록하지 않고 다시 전송 대상으로 남는다")
    void keepsResendWhenCommentWrittenAgainDuringDelete() {
        ComplaintDetail detail = sentFeedback("분리수거장은 지하 1층입니다");
        reload(detail).deleteComment();
        entityManager.flush();
        LocalDateTime readVersion = complaintDetailRepository.findPendingAiFeedbackDeletions(PageRequest.of(0, 10))
            .getFirst().updatedAt();
        reload(detail).updateComment("다시 안내드립니다");
        entityManager.flush();

        int marked = complaintDetailRepository.markAiFeedbackDeleted(detail.getId(), readVersion);

        assertThat(marked).isZero();
        assertThat(complaintDetailRepository.findPendingAiFeedback(UNKNOWN, PageRequest.of(0, 10)).getContent())
            .extracting(ComplaintDetail::getId).containsExactly(detail.getId());
    }

    private ComplaintDetail sentFeedback(String comment) {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintStatus.DONE, comment);
        complaintDetailRepository.markAiFeedbackSent(detail.getId(), reload(detail).getUpdatedAt());
        entityManager.clear();
        return detail;
    }

    private ComplaintDetail reload(ComplaintDetail detail) {
        entityManager.clear();
        return complaintDetailRepository.findById(detail.getId()).orElseThrow();
    }

    private ComplaintDetail saveComplaintWithDetail(ComplaintStatus status, String comment) {
        return saveComplaintWithDetail(ComplaintType.QA, "분리수거 위치 문의", status, comment);
    }

    private ComplaintDetail saveComplaintWithDetail(ComplaintType type, String symptom, ComplaintStatus status,
                                                    String comment) {
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
            .type(type)
            .urgency(0)
            .roomNo("302")
            .build());
        complaint.changeStatus(status);
        ComplaintDetail detail = ComplaintDetail.builder()
            .complaint(complaint)
            .location("공용 공간")
            .symptom(symptom)
            .aiSummary("분리수거장 위치를 물어봄")
            .build();
        entityManager.persist(detail);
        if (comment != null) {
            detail.updateComment(comment);
        }
        return entityManager.persistFlushFind(detail);
    }
}
