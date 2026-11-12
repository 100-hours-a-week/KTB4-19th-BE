package com.homes.zipsai.building.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.domain.ComplaintStatus;
import com.homes.zipsai.building.domain.ComplaintType;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.ComplaintDetailRepository;
import com.homes.zipsai.building.repository.ComplaintRepository;
import com.homes.zipsai.conversation.ai.AiComplaintFeedbackBatchRequest;
import com.homes.zipsai.conversation.ai.AiComplaintFeedbackClient;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.repository.UserRepository;

@SpringBootTest(properties = "app.complaint.ai-feedback.concurrency=3")
@SpringBatchTest
class ComplaintAiFeedbackParallelJobTest {

    private static final int PARTITIONS = 3;

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

    private final List<ComplaintDetail> details = new ArrayList<>();
    private final List<Complaint> complaints = new ArrayList<>();
    private final List<Conversation> conversations = new ArrayList<>();
    private User manager;
    private User resident;
    private Building building;

    @BeforeEach
    void setUp() {
        jobOperatorTestUtils.setJob(complaintAiFeedbackJob);
        manager = userRepository.save(new User("parallel-manager@example.com", "password", "관리자", null));
        resident = userRepository.save(new User("parallel-resident@example.com", "password", "입주민", null));
        building = buildingRepository.save(Building.builder()
            .manager(manager)
            .roadAddress("서울시 강남구 테헤란로 1")
            .buildingName("집사이 빌딩")
            .build());
    }

    @AfterEach
    void tearDown() {
        complaintDetailRepository.deleteAll(details);
        complaintRepository.deleteAll(complaints);
        conversationRepository.deleteAll(conversations);
        buildingRepository.delete(building);
        userRepository.deleteAll(List.of(manager, resident));
    }

    @Test
    @DisplayName("파티션마다 AI 전송을 동시에 실행하고 모든 민원을 전송 완료로 남긴다")
    void sendsPartitionsConcurrently() throws Exception {
        for (int i = 0; i < 7; i++) {
            saveCompletedComplaint("코멘트 " + i);
        }
        CountDownLatch allPartitionsSending = new CountDownLatch(PARTITIONS);
        Set<Boolean> overlapped = ConcurrentHashMap.newKeySet();
        willAnswer(invocation -> {
            allPartitionsSending.countDown();
            overlapped.add(allPartitionsSending.await(5, TimeUnit.SECONDS));
            return null;
        }).given(aiComplaintFeedbackClient).send(any(AiComplaintFeedbackBatchRequest.class));

        JobExecution execution = jobOperatorTestUtils.startJob(
            jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        verify(aiComplaintFeedbackClient, times(PARTITIONS)).send(any(AiComplaintFeedbackBatchRequest.class));
        assertThat(overlapped).containsExactly(true);
        assertThat(details).allSatisfy(detail -> assertThat(
            complaintDetailRepository.findById(detail.getId()).orElseThrow().getAiFeedbackSentAt()).isNotNull());
    }

    @Test
    @DisplayName("병렬로 보내도 같은 민원 답변을 두 번 보내지 않는다")
    void sendsEachItemOnce() throws Exception {
        for (int i = 0; i < 10; i++) {
            saveCompletedComplaint("코멘트 " + i);
        }
        List<Long> sentIds = new CopyOnWriteArrayList<>();
        willAnswer(invocation -> {
            AiComplaintFeedbackBatchRequest request = invocation.getArgument(0);
            request.items().forEach(item -> sentIds.add(item.complaintDetailId()));
            return null;
        }).given(aiComplaintFeedbackClient).send(any(AiComplaintFeedbackBatchRequest.class));

        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(sentIds).doesNotHaveDuplicates()
            .containsExactlyInAnyOrderElementsOf(details.stream().map(ComplaintDetail::getId).toList());
    }

    private void saveCompletedComplaint(String comment) {
        Conversation conversation = conversationRepository.save(Conversation.builder()
            .user(resident)
            .type(ConversationType.COMPLAINT)
            .title("분리수거 어디서 해요")
            .build());
        conversations.add(conversation);
        Complaint complaint = complaintRepository.save(Complaint.builder()
            .conversation(conversation)
            .user(resident)
            .building(building)
            .title("분리수거 문의")
            .type(ComplaintType.QA)
            .urgency(0)
            .roomNo("302")
            .build());
        complaint.changeStatus(ComplaintStatus.DONE);
        complaints.add(complaintRepository.save(complaint));
        ComplaintDetail detail = ComplaintDetail.builder()
            .complaint(complaint)
            .location("공용 공간")
            .symptom("분리수거 위치 문의")
            .aiSummary("분리수거장 위치를 물어봄")
            .build();
        detail.updateComment(comment);
        details.add(complaintDetailRepository.save(detail));
    }
}
