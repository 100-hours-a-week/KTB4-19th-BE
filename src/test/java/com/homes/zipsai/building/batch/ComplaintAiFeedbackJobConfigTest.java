package com.homes.zipsai.building.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintContent;
import com.homes.zipsai.building.domain.ComplaintDetail;
import com.homes.zipsai.building.domain.ComplaintStatus;
import com.homes.zipsai.building.domain.ComplaintType;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.ComplaintDetailRepository;
import com.homes.zipsai.building.repository.ComplaintRepository;
import com.homes.zipsai.conversation.ai.AiComplaintDraft;
import com.homes.zipsai.conversation.ai.AiComplaintFeedbackBatchRequest;
import com.homes.zipsai.conversation.ai.AiComplaintFeedbackClient;
import com.homes.zipsai.conversation.ai.AiComplaintFeedbackDeleteRequest;
import com.homes.zipsai.conversation.ai.AiComplaintFeedbackRequest;
import com.homes.zipsai.conversation.ai.AiComplaintFeedbackResponse;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationType;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.global.exception.InternalServerException;
import com.homes.zipsai.global.util.TimeUtils;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.repository.UserRepository;

@SpringBootTest
@SpringBatchTest
@Transactional
class ComplaintAiFeedbackJobConfigTest {

    private static final String UNKNOWN = ComplaintContent.from(AiComplaintDraft.EMPTY).symptom();

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
        saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        verify(aiComplaintFeedbackClient, times(1)).send(any(AiComplaintFeedbackBatchRequest.class));
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
    @DisplayName("AI가 접수하면 청크의 모든 민원을 전송 완료로 남긴다")
    void marksWholeChunkSentWhenAccepted() throws Exception {
        ComplaintDetail first = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        ComplaintDetail second = saveComplaintWithDetail(ComplaintStatus.DONE, "주차 등록은 관리사무소에서 해요");

        JobExecution execution = jobOperatorTestUtils.startJob(
            jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(sentAt(first)).isNotNull();
        assertThat(sentAt(second)).isNotNull();
    }

    @Test
    @DisplayName("AI가 접수를 거절하면 전송 완료를 남기지 않는다")
    void doesNotMarkSentWhenRejected() throws Exception {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        doThrow(new InternalServerException())
            .when(aiComplaintFeedbackClient).send(any(AiComplaintFeedbackBatchRequest.class));

        JobExecution execution = jobOperatorTestUtils.startJob(
            jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.FAILED.getExitCode());
        assertThat(sentAt(detail)).isNull();
    }

    @Test
    @DisplayName("AI 요청마다 다른 trace_id를 보낸다")
    void sendsUniqueTraceIdPerRequest() throws Exception {
        IntStream.range(0, 101).forEach(i -> saveComplaintWithDetail(ComplaintStatus.DONE, "답변 " + i));

        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        ArgumentCaptor<AiComplaintFeedbackBatchRequest> captor =
            ArgumentCaptor.forClass(AiComplaintFeedbackBatchRequest.class);
        verify(aiComplaintFeedbackClient, times(2)).send(captor.capture());
        List<String> traceIds = captor.getAllValues().stream().map(AiComplaintFeedbackBatchRequest::traceId).toList();
        assertThat(traceIds).doesNotContainNull().doesNotHaveDuplicates();
    }

    private LocalDateTime sentAt(ComplaintDetail detail) {
        return complaintDetailRepository.findById(detail.getId()).orElseThrow().getAiFeedbackSentAt();
    }

    @Test
    @DisplayName("질의(QA)는 위치와 이슈 유형을 비워서 보낸다")
    void sendsQaWithoutLocationAndIssueType() throws Exception {
        saveComplaintWithDetail(ComplaintType.QA, "leak", "공용 공간", "분리수거장은 지하 1층입니다");

        AiComplaintFeedbackRequest sent = sendAndCaptureSingleItem();

        assertThat(sent.complaintType()).isEqualTo("QA");
        assertThat(sent.location()).isNull();
        assertThat(sent.issueType()).isNull();
    }

    @Test
    @DisplayName("민원은 위치와 대화의 이슈 유형을 그대로 보낸다")
    void sendsComplaintWithLocationAndIssueType() throws Exception {
        saveComplaintWithDetail(ComplaintType.COMPLAINT, "leak", "지하주차장 B2", "배관 누수를 확인했습니다");

        AiComplaintFeedbackRequest sent = sendAndCaptureSingleItem();

        assertThat(sent.complaintType()).isEqualTo("COMPLAINT");
        assertThat(sent.location()).isEqualTo("지하주차장 B2");
        assertThat(sent.issueType()).isEqualTo("leak");
    }

    @Test
    @DisplayName("민원 위치를 알 수 없으면 위치를 비워서 보낸다")
    void sendsComplaintWithoutUnknownLocation() throws Exception {
        saveComplaintWithDetail(ComplaintType.COMPLAINT, "leak", UNKNOWN, "배관 누수를 확인했습니다");

        AiComplaintFeedbackRequest sent = sendAndCaptureSingleItem();

        assertThat(sent.location()).isNull();
    }

    @Test
    @DisplayName("증상을 알 수 없는 민원도 답변이 있으면 전송한다")
    void sendsComplaintWithUnknownSymptom() throws Exception {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintType.COMPLAINT, "leak", "지하주차장 B2",
            "배관 누수를 확인했습니다");
        ReflectionTestUtils.setField(detail, "symptom", UNKNOWN);
        complaintDetailRepository.saveAndFlush(detail);

        AiComplaintFeedbackRequest sent = sendAndCaptureSingleItem();

        assertThat(sent.complaintDetailId()).isEqualTo(detail.getId());
        assertThat(sent.symptom()).isEqualTo(UNKNOWN);
    }

    @Test
    @DisplayName("전송 후 수정한 답변은 다시 전송한다")
    void resendsEditedFeedback() throws Exception {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());
        ComplaintDetail reloaded = complaintDetailRepository.findById(detail.getId()).orElseThrow();
        reloaded.updateComment("분리수거장은 지하 2층으로 옮겼습니다");
        complaintDetailRepository.saveAndFlush(reloaded);

        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        ArgumentCaptor<AiComplaintFeedbackBatchRequest> captor =
            ArgumentCaptor.forClass(AiComplaintFeedbackBatchRequest.class);
        verify(aiComplaintFeedbackClient, times(2)).send(captor.capture());
        assertThat(captor.getAllValues().getLast().items().getFirst().comment())
            .isEqualTo("분리수거장은 지하 2층으로 옮겼습니다");
    }

    @Test
    @DisplayName("전송한 답변을 삭제하면 AI에 삭제를 요청하고 전송 기록을 지운다")
    void deletesSentFeedbackFromAi() throws Exception {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());
        ComplaintDetail reloaded = complaintDetailRepository.findById(detail.getId()).orElseThrow();
        reloaded.deleteComment();
        complaintDetailRepository.saveAndFlush(reloaded);

        JobExecution execution = jobOperatorTestUtils.startJob(
            jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        ArgumentCaptor<AiComplaintFeedbackDeleteRequest> captor =
            ArgumentCaptor.forClass(AiComplaintFeedbackDeleteRequest.class);
        verify(aiComplaintFeedbackClient).delete(captor.capture());
        assertThat(captor.getValue().items()).singleElement().satisfies(item -> {
            assertThat(item.complaintDetailId()).isEqualTo(detail.getId());
            assertThat(item.buildingId()).isEqualTo(building.getId());
            assertThat(item.updatedAt()).isNotNull();
        });
        assertThat(sentAt(detail)).isNull();
    }

    @Test
    @DisplayName("전송한 적 없는 답변을 삭제하면 AI에 삭제를 요청하지 않는다")
    void doesNotDeleteNeverSentFeedback() throws Exception {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        detail.deleteComment();
        complaintDetailRepository.saveAndFlush(detail);

        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        verify(aiComplaintFeedbackClient, never()).delete(any(AiComplaintFeedbackDeleteRequest.class));
    }

    @Test
    @DisplayName("AI 삭제 요청이 실패하면 전송 기록을 남겨 다음 배치에서 다시 요청한다")
    void keepsSentRecordWhenDeleteFails() throws Exception {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());
        ComplaintDetail reloaded = complaintDetailRepository.findById(detail.getId()).orElseThrow();
        reloaded.deleteComment();
        complaintDetailRepository.saveAndFlush(reloaded);
        doThrow(new InternalServerException())
            .when(aiComplaintFeedbackClient).delete(any(AiComplaintFeedbackDeleteRequest.class));

        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(sentAt(detail)).isNotNull();
    }

    @Test
    @DisplayName("AI가 건별 결과를 주면 성공한 답변만 전송 완료로 남기고 실패한 답변은 다음 실행에서 다시 보낸다")
    void marksOnlySucceededItemsSent() throws Exception {
        ComplaintDetail succeeded = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        ComplaintDetail failed = saveComplaintWithDetail(ComplaintStatus.DONE, "주차 등록은 관리사무소에서 해요");
        given(aiComplaintFeedbackClient.send(any(AiComplaintFeedbackBatchRequest.class))).willAnswer(invocation ->
            results(invocation.<AiComplaintFeedbackBatchRequest>getArgument(0).traceId(),
                result(succeeded, "succeeded", null), result(failed, "failed", "EMBEDDING_FAILED")));

        JobExecution execution = jobOperatorTestUtils.startJob(
            jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(sentAt(succeeded)).isNotNull();
        assertThat(sentAt(failed)).isNull();

        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        ArgumentCaptor<AiComplaintFeedbackBatchRequest> captor =
            ArgumentCaptor.forClass(AiComplaintFeedbackBatchRequest.class);
        verify(aiComplaintFeedbackClient, times(2)).send(captor.capture());
        assertThat(captor.getAllValues().getLast().items())
            .extracting(AiComplaintFeedbackRequest::complaintDetailId)
            .containsExactly(failed.getId());
    }

    @Test
    @DisplayName("AI가 의도적으로 반영하지 않은 답변은 다시 보내지 않도록 전송 완료로 남긴다")
    void marksSkippedItemSent() throws Exception {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintStatus.DONE, "네");
        given(aiComplaintFeedbackClient.send(any(AiComplaintFeedbackBatchRequest.class))).willAnswer(invocation ->
            results(invocation.<AiComplaintFeedbackBatchRequest>getArgument(0).traceId(),
                result(detail, "skipped", "COMMENT_TOO_SHORT")));

        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(sentAt(detail)).isNotNull();
    }

    @Test
    @DisplayName("AI 응답에 결과가 빠진 답변은 전송 완료로 남기지 않는다")
    void doesNotMarkItemMissingFromResults() throws Exception {
        ComplaintDetail answered = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        ComplaintDetail missing = saveComplaintWithDetail(ComplaintStatus.DONE, "주차 등록은 관리사무소에서 해요");
        given(aiComplaintFeedbackClient.send(any(AiComplaintFeedbackBatchRequest.class))).willAnswer(invocation ->
            results(invocation.<AiComplaintFeedbackBatchRequest>getArgument(0).traceId(),
                result(answered, "succeeded", null)));

        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(sentAt(answered)).isNotNull();
        assertThat(sentAt(missing)).isNull();
    }

    @Test
    @DisplayName("AI가 삭제 실패를 응답하면 전송 기록을 남겨 다음 실행에서 다시 삭제를 요청한다")
    void keepsSentRecordWhenDeleteResultFailed() throws Exception {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());
        ComplaintDetail reloaded = complaintDetailRepository.findById(detail.getId()).orElseThrow();
        reloaded.deleteComment();
        complaintDetailRepository.saveAndFlush(reloaded);
        given(aiComplaintFeedbackClient.delete(any(AiComplaintFeedbackDeleteRequest.class))).willAnswer(invocation ->
            results(invocation.<AiComplaintFeedbackDeleteRequest>getArgument(0).traceId(),
                result(detail, "failed", "VECTOR_STORE_FAILED")));

        JobExecution execution = jobOperatorTestUtils.startJob(
            jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
        assertThat(sentAt(detail)).isNotNull();
    }

    @Test
    @DisplayName("AI가 삭제 성공을 응답하면 전송 기록을 지운다")
    void clearsSentRecordWhenDeleteResultSucceeded() throws Exception {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());
        ComplaintDetail reloaded = complaintDetailRepository.findById(detail.getId()).orElseThrow();
        reloaded.deleteComment();
        complaintDetailRepository.saveAndFlush(reloaded);
        given(aiComplaintFeedbackClient.delete(any(AiComplaintFeedbackDeleteRequest.class))).willAnswer(invocation ->
            results(invocation.<AiComplaintFeedbackDeleteRequest>getArgument(0).traceId(),
                result(detail, "succeeded", null)));

        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(sentAt(detail)).isNull();
    }

    @Test
    @DisplayName("AI 응답의 updated_at이 보낸 버전과 다르면 전송 완료로 남기지 않는다")
    void doesNotMarkSentWhenVersionDiffers() throws Exception {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        OffsetDateTime otherVersion = sentVersion(detail).minusMinutes(1);
        given(aiComplaintFeedbackClient.send(any(AiComplaintFeedbackBatchRequest.class))).willAnswer(invocation ->
            results(invocation.<AiComplaintFeedbackBatchRequest>getArgument(0).traceId(),
                new AiComplaintFeedbackResponse.Result(detail.getId(), otherVersion, "succeeded", null)));

        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(sentAt(detail)).isNull();
    }

    @Test
    @DisplayName("AI 응답의 updated_at이 다른 시간대여도 같은 시각이면 보낸 버전으로 인정한다")
    void acceptsSameInstantInOtherOffset() throws Exception {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        OffsetDateTime utcVersion = sentVersion(detail).withOffsetSameInstant(ZoneOffset.UTC);
        given(aiComplaintFeedbackClient.send(any(AiComplaintFeedbackBatchRequest.class))).willAnswer(invocation ->
            results(invocation.<AiComplaintFeedbackBatchRequest>getArgument(0).traceId(),
                new AiComplaintFeedbackResponse.Result(detail.getId(), utcVersion, "succeeded", null)));

        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(sentAt(detail)).isNotNull();
    }

    @Test
    @DisplayName("AI 삭제 응답의 updated_at이 삭제 요청 버전과 다르면 삭제 완료로 남기지 않는다")
    void keepsSentRecordWhenDeleteVersionDiffers() throws Exception {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintStatus.DONE, "분리수거장은 지하 1층입니다");
        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());
        ComplaintDetail reloaded = complaintDetailRepository.findById(detail.getId()).orElseThrow();
        reloaded.deleteComment();
        complaintDetailRepository.saveAndFlush(reloaded);
        OffsetDateTime otherVersion = sentVersion(detail).plusSeconds(1);
        given(aiComplaintFeedbackClient.delete(any(AiComplaintFeedbackDeleteRequest.class))).willAnswer(invocation ->
            results(invocation.<AiComplaintFeedbackDeleteRequest>getArgument(0).traceId(),
                new AiComplaintFeedbackResponse.Result(detail.getId(), otherVersion, "succeeded", null)));

        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());

        assertThat(sentAt(detail)).isNotNull();
    }

    private OffsetDateTime sentVersion(ComplaintDetail detail) {
        return TimeUtils.toOffsetDateTime(complaintDetailRepository.findById(detail.getId()).orElseThrow()
            .getUpdatedAt());
    }

    private static AiComplaintFeedbackResponse results(String traceId, AiComplaintFeedbackResponse.Result... results) {
        return new AiComplaintFeedbackResponse(traceId, List.of(results));
    }

    private static AiComplaintFeedbackResponse.Result result(ComplaintDetail detail, String status, String reasonCode) {
        return new AiComplaintFeedbackResponse.Result(detail.getId(), null, status, reasonCode);
    }

    private AiComplaintFeedbackRequest sendAndCaptureSingleItem() throws Exception {
        jobOperatorTestUtils.startJob(jobOperatorTestUtils.getUniqueJobParametersBuilder().toJobParameters());
        ArgumentCaptor<AiComplaintFeedbackBatchRequest> captor =
            ArgumentCaptor.forClass(AiComplaintFeedbackBatchRequest.class);
        verify(aiComplaintFeedbackClient).send(captor.capture());
        return captor.getValue().items().getFirst();
    }

    private ComplaintDetail saveComplaintWithDetail(ComplaintStatus status, String comment) {
        ComplaintDetail detail = saveComplaintWithDetail(ComplaintType.QA, null, "공용 공간", comment);
        detail.getComplaint().changeStatus(status);
        complaintRepository.save(detail.getComplaint());
        return detail;
    }

    private ComplaintDetail saveComplaintWithDetail(ComplaintType type, String issueType, String location,
                                                    String comment) {
        Conversation conversation = Conversation.builder()
            .user(resident)
            .type(ConversationType.COMPLAINT)
            .title("분리수거 어디서 해요")
            .build();
        ReflectionTestUtils.setField(conversation, "draftIssueType", issueType);
        conversationRepository.save(conversation);
        Complaint complaint = complaintRepository.save(Complaint.builder()
            .conversation(conversation)
            .user(resident)
            .building(building)
            .title("분리수거 문의")
            .type(type)
            .urgency(0)
            .roomNo("302")
            .build());
        complaint.changeStatus(ComplaintStatus.DONE);
        complaintRepository.save(complaint);
        ComplaintDetail detail = ComplaintDetail.builder()
            .complaint(complaint)
            .location(location)
            .symptom("분리수거 위치 문의")
            .aiSummary("분리수거장 위치를 물어봄")
            .build();
        if (comment != null) {
            detail.updateComment(comment);
        }
        return complaintDetailRepository.save(detail);
    }
}
