package com.homes.zipsai.building.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.domain.ComplaintStatus;
import com.homes.zipsai.building.domain.ComplaintType;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.ComplaintDetailRepository;
import com.homes.zipsai.building.repository.ComplaintRepository;
import com.homes.zipsai.conversation.ai.AiComplaintFeedbackBatchRequest;
import com.homes.zipsai.conversation.ai.AiComplaintFeedbackBatchResponse;
import com.homes.zipsai.conversation.ai.AiComplaintFeedbackClient;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.repository.UserRepository;

@SpringBootTest
@SpringBatchTest
@Transactional
class ComplaintAiFeedbackJobConfigTest {

    @Autowired
    JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    @Qualifier("complaintAiFeedbackJob")
    Job complaintAiFeedbackJob;

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

    @MockitoBean
    AiComplaintFeedbackClient aiComplaintFeedbackClient;

    private User manager;

    private User resident;

    private Building building;

    @BeforeEach
    void setUp() {
        jobOperatorTestUtils.setJob(complaintAiFeedbackJob);
        manager = userRepository.save(new User("manager@example.com", "password", "관리자", null));
        resident = userRepository.save(new User("resident@example.com", "password", "입주민", null));
        building = buildingRepository.save(Building.builder()
            .manager(manager)
            .roadAddress("서울시 강남구 테헤란로 1")
            .buildingName("집사이 빌딩")
            .build());
        given(aiComplaintFeedbackClient.send(any(AiComplaintFeedbackBatchRequest.class)))
            .willAnswer(invocation -> respondAll(invocation.getArgument(0), "ACCEPTED"));
    }

    @Test
    @DisplayName("완료되고 코멘트가 달린 민원을 AI로 전송하고 전송 시각을 남긴다")
    void sendsCompletedComplaintFeedback() throws Exception {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");

        JobExecution execution = jobOperatorTestUtils.startJob(
            jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        verify(aiComplaintFeedbackClient).send(any(AiComplaintFeedbackBatchRequest.class));
        ComplaintDetail reloaded = complaintDetailRepository.findById(detail.getId()).orElseThrow();
        assertThat(reloaded.getAiFeedbackSentAt()).isNotNull();
    }

    @Test
    @DisplayName("이미 전송한 민원은 다시 전송하지 않는다")
    void doesNotResendAlreadySentFeedback() throws Exception {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        detail.markAiFeedbackSent(LocalDateTime.now());
        complaintDetailRepository.save(detail);

        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        verify(aiComplaintFeedbackClient, never()).send(any(AiComplaintFeedbackBatchRequest.class));
    }

    @Test
    @DisplayName("코멘트가 없는 완료 민원은 전송하지 않는다")
    void doesNotSendWithoutComment() throws Exception {
        saveComplaintWithDetail(ComplaintStatus.DONE, null);

        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        verify(aiComplaintFeedbackClient, never()).send(any(AiComplaintFeedbackBatchRequest.class));
    }

    @Test
    @DisplayName("한 청크의 민원을 AI 요청 한 번에 묶어서 보낸다")
    void sendsChunkInSingleRequest() throws Exception {
        ComplaintDetail first = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        ComplaintDetail second = saveComplaintWithDetail(ComplaintStatus.DONE, "주차 등록은 관리사무소에서 해요");
        ComplaintDetail third = saveComplaintWithDetail(ComplaintStatus.DONE, "세탁실은 24시간 이용 가능해요");

        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        ArgumentCaptor<AiComplaintFeedbackBatchRequest> captor =
            ArgumentCaptor.forClass(AiComplaintFeedbackBatchRequest.class);
        verify(aiComplaintFeedbackClient, times(1)).send(captor.capture());
        assertThat(captor.getValue().items())
            .extracting(item -> item.complaintDetailId())
            .containsExactlyInAnyOrder(first.getId(), second.getId(), third.getId());
    }

    @Test
    @DisplayName("AI가 받은 건만 전송 완료로 남기고 재시도 대상과 응답에 없는 건은 다음 배치로 넘긴다")
    void marksOnlyDeliveredFeedback() throws Exception {
        ComplaintDetail accepted = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        ComplaintDetail duplicate = saveComplaintWithDetail(ComplaintStatus.DONE, "주차 등록은 관리사무소에서 해요");
        ComplaintDetail retryable = saveComplaintWithDetail(ComplaintStatus.DONE, "세탁실은 24시간 이용 가능해요");
        ComplaintDetail missing = saveComplaintWithDetail(ComplaintStatus.DONE, "택배함은 1층 로비에 있어요");
        given(aiComplaintFeedbackClient.send(any(AiComplaintFeedbackBatchRequest.class)))
            .willReturn(respond(Map.of(
                accepted.getId(), "ACCEPTED",
                duplicate.getId(), "DUPLICATE",
                retryable.getId(), "RETRYABLE")));

        JobExecution execution = jobOperatorTestUtils.startJob(
            jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(sentAt(accepted)).isNotNull();
        assertThat(sentAt(duplicate)).isNotNull();
        assertThat(sentAt(retryable)).isNull();
        assertThat(sentAt(missing)).isNull();
    }

    @Test
    @DisplayName("AI가 거부한 건은 다시 보내지 않도록 전송 완료로 남긴다")
    void marksRejectedFeedbackAsSent() throws Exception {
        ComplaintDetail rejected = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        given(aiComplaintFeedbackClient.send(any(AiComplaintFeedbackBatchRequest.class)))
            .willAnswer(invocation -> respondAll(invocation.getArgument(0), "REJECTED"));

        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(sentAt(rejected)).isNotNull();
    }

    private LocalDateTime sentAt(ComplaintDetail detail) {
        return complaintDetailRepository.findById(detail.getId()).orElseThrow().getAiFeedbackSentAt();
    }

    private static AiComplaintFeedbackBatchResponse respondAll(AiComplaintFeedbackBatchRequest request, String status) {
        return new AiComplaintFeedbackBatchResponse(request.items().stream()
            .map(item -> new AiComplaintFeedbackBatchResponse.Result(item.complaintDetailId(), status, null))
            .toList());
    }

    private static AiComplaintFeedbackBatchResponse respond(Map<Long, String> statuses) {
        List<AiComplaintFeedbackBatchResponse.Result> results = statuses.entrySet().stream()
            .map(entry -> new AiComplaintFeedbackBatchResponse.Result(entry.getKey(), entry.getValue(), null))
            .toList();
        return new AiComplaintFeedbackBatchResponse(results);
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
        complaintRepository.save(complaint);
        ComplaintDetail detail = ComplaintDetail.builder()
            .complaint(complaint)
            .location("공용 공간")
            .symptom("분리수거 위치 문의")
            .aiSummary("분리수거장 위치를 물어봄")
            .build();
        if (comment != null) {
            detail.updateComment(comment);
        }
        return complaintDetailRepository.save(detail);
    }
}
