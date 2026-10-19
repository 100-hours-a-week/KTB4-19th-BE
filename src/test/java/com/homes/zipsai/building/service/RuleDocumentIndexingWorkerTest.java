package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.RuleDocument;
import com.homes.zipsai.building.domain.RuleDocumentIndexingOutbox;
import com.homes.zipsai.building.domain.RuleDocumentIndexingOutboxStatus;
import com.homes.zipsai.building.domain.RuleDocumentIndexingStatus;
import com.homes.zipsai.building.repository.RuleDocumentIndexingOutboxRepository;
import com.homes.zipsai.building.repository.RuleDocumentRepository;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.conversation.ai.AiIndexingClient;
import com.homes.zipsai.conversation.ai.AiIndexingRequest;
import com.homes.zipsai.conversation.ai.AiIndexingResponse;
import com.homes.zipsai.conversation.ai.AiIndexingResponse.Result;
import com.homes.zipsai.global.exception.AiUnavailableException;
import com.homes.zipsai.global.exception.InternalServerException;
import com.homes.zipsai.user.domain.User;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RuleDocumentIndexingWorkerTest {

    private static final long OUTBOX_ID = 1L;
    private static final long BUILDING_ID = 10L;
    private static final long DOCUMENT_ID = 100L;
    private static final String DOC_ID = "100";
    private static final String TRACE_ID = "request-trace-id";

    @Mock
    RuleDocumentIndexingOutboxRepository outboxRepository;

    @Mock
    RuleDocumentRepository documentRepository;

    @Mock
    ObjectProvider<AiIndexingClient> aiIndexingClientProvider;

    @Mock
    AiIndexingClient aiIndexingClient;

    @Mock
    PlatformTransactionManager transactionManager;

    RuleDocumentIndexingWorker worker;
    Building building;
    RuleDocument document;

    @BeforeEach
    void setUp() {
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        given(aiIndexingClientProvider.getIfAvailable()).willReturn(aiIndexingClient);
        worker = new RuleDocumentIndexingWorker(outboxRepository, documentRepository,
            new StorageProperties(null, null, "test-bucket", 0, 0), aiIndexingClientProvider,
            new TransactionTemplate(transactionManager));

        User manager = new User("manager@zipsai.com", "password", "관리자", null);
        building = new Building(manager, "서울시 강남구", "A타워");
        ReflectionTestUtils.setField(building, "id", BUILDING_ID);
        File file = new File("documents/rule.pdf", 100, "application/pdf", "rule.pdf");
        document = new RuleDocument(building, file, "관리규약", "", 1);
        ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
        given(documentRepository.findWithAttachmentById(DOCUMENT_ID)).willReturn(Optional.of(document));
        given(documentRepository.findValidIdsByBuildingId(BUILDING_ID)).willReturn(List.of(DOCUMENT_ID, 99L));
    }

    @Test
    @DisplayName("대기 중인 색인을 보낼 때 최신 유효 문서 목록과 저장해 둔 trace_id를 담는다")
    void sendsIndexingRequestWithLatestValidDocuments() {
        RuleDocumentIndexingOutbox outbox = givenIndexOutbox();
        givenResponse(new Result(AiIndexingResponse.KIND_INDEX, DOC_ID, "succeeded", null));

        worker.processDue();

        ArgumentCaptor<AiIndexingRequest> captor = ArgumentCaptor.forClass(AiIndexingRequest.class);
        then(aiIndexingClient).should().index(captor.capture());
        AiIndexingRequest request = captor.getValue();
        assertThat(request.buildingId()).isEqualTo(BUILDING_ID);
        assertThat(request.docId()).isEqualTo(DOC_ID);
        assertThat(request.title()).isEqualTo("관리규약");
        assertThat(request.fileKey()).isEqualTo("s3://test-bucket/documents/rule.pdf");
        assertThat(request.validDocIds()).containsExactly(DOC_ID, "99");
        assertThat(request.traceId()).isEqualTo(TRACE_ID);
        assertThat(outbox.getStatus()).isEqualTo(RuleDocumentIndexingOutboxStatus.DONE);
    }

    @Test
    @DisplayName("AI가 색인 성공을 응답하면 문서 색인 상태를 완료로 저장한다")
    void savesIndexedStatusOnSuccess() {
        givenIndexOutbox();
        givenResponse(new Result(AiIndexingResponse.KIND_INDEX, DOC_ID, "succeeded", null),
            new Result(AiIndexingResponse.KIND_CLEANUP, null, "succeeded", null));

        worker.processDue();

        then(documentRepository).should()
            .updateIndexingResult(DOCUMENT_ID, 1, RuleDocumentIndexingStatus.INDEXED, null);
    }

    @Test
    @DisplayName("AI가 색인 실패를 응답하면 문서 색인 상태를 실패로 두고 실패 사유를 저장한다")
    void savesFailedStatusWithReasonCode() {
        RuleDocumentIndexingOutbox outbox = givenIndexOutbox();
        givenResponse(new Result(AiIndexingResponse.KIND_INDEX, DOC_ID, "failed", "PARSE_FAILED"),
            new Result(AiIndexingResponse.KIND_CLEANUP, null, "skipped", "INDEX_FAILED"));

        worker.processDue();

        then(documentRepository).should()
            .updateIndexingResult(DOCUMENT_ID, 1, RuleDocumentIndexingStatus.FAILED, "PARSE_FAILED");
        assertThat(outbox.getStatus()).isEqualTo(RuleDocumentIndexingOutboxStatus.FAILED);
        assertThat(outbox.getLastError()).isEqualTo("PARSE_FAILED");
    }

    @Test
    @DisplayName("AI가 의도적으로 반영하지 않으면 건너뜀 상태와 사유를 저장한다")
    void savesSkippedStatus() {
        givenIndexOutbox();
        givenResponse(new Result(AiIndexingResponse.KIND_INDEX, DOC_ID, "skipped", "EMPTY_DOCUMENT"));

        worker.processDue();

        then(documentRepository).should()
            .updateIndexingResult(DOCUMENT_ID, 1, RuleDocumentIndexingStatus.SKIPPED, "EMPTY_DOCUMENT");
    }

    @Test
    @DisplayName("응답에 이 문서의 색인 결과가 없으면 실패로 저장한다")
    void failsWhenResultMissing() {
        givenIndexOutbox();
        givenResponse(new Result(AiIndexingResponse.KIND_INDEX, "999", "succeeded", null));

        worker.processDue();

        then(documentRepository).should().updateIndexingResult(DOCUMENT_ID, 1, RuleDocumentIndexingStatus.FAILED,
            RuleDocumentIndexingWorker.MISSING_RESULT);
    }

    @Test
    @DisplayName("AI가 결과 없이 접수만 하면 문서 색인 상태는 대기로 두고 아웃박스만 완료한다")
    void keepsPendingWhenAcceptedWithoutResult() {
        RuleDocumentIndexingOutbox outbox = givenIndexOutbox();
        given(aiIndexingClient.index(any())).willReturn(AiIndexingResponse.empty());

        worker.processDue();

        assertThat(outbox.getStatus()).isEqualTo(RuleDocumentIndexingOutboxStatus.DONE);
        then(documentRepository).should(never()).updateIndexingResult(anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("AI에 연결하지 못하면 문서 상태는 그대로 두고 백오프 후 다시 보낸다")
    void schedulesRetryWhenAiUnavailable() {
        RuleDocumentIndexingOutbox outbox = givenIndexOutbox();
        given(aiIndexingClient.index(any())).willThrow(new AiUnavailableException());
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        worker.processDue();

        assertThat(outbox.getStatus()).isEqualTo(RuleDocumentIndexingOutboxStatus.PENDING);
        assertThat(outbox.getRetryCount()).isEqualTo(1);
        assertThat(outbox.getNextRetryAt()).isAfter(before.plus(RuleDocumentIndexingWorker.BASE_RETRY_DELAY));
        then(documentRepository).should(never()).updateIndexingResult(anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("재시도 횟수를 넘기면 아웃박스와 문서 색인 상태를 실패로 남긴다")
    void failsAfterMaxRetries() {
        RuleDocumentIndexingOutbox outbox = givenIndexOutbox();
        ReflectionTestUtils.setField(outbox, "retryCount", RuleDocumentIndexingWorker.MAX_RETRY_COUNT);
        given(aiIndexingClient.index(any())).willThrow(new AiUnavailableException());

        worker.processDue();

        assertThat(outbox.getStatus()).isEqualTo(RuleDocumentIndexingOutboxStatus.FAILED);
        then(documentRepository).should().updateIndexingResult(DOCUMENT_ID, 1, RuleDocumentIndexingStatus.FAILED,
            RuleDocumentIndexingWorker.AI_UNAVAILABLE);
    }

    @Test
    @DisplayName("AI가 요청을 거절하면 재시도하지 않고 실패로 남긴다")
    void failsWithoutRetryWhenRejected() {
        RuleDocumentIndexingOutbox outbox = givenIndexOutbox();
        given(aiIndexingClient.index(any())).willThrow(new InternalServerException());

        worker.processDue();

        assertThat(outbox.getStatus()).isEqualTo(RuleDocumentIndexingOutboxStatus.FAILED);
        assertThat(outbox.getRetryCount()).isZero();
        then(documentRepository).should().updateIndexingResult(DOCUMENT_ID, 1, RuleDocumentIndexingStatus.FAILED,
            RuleDocumentIndexingWorker.AI_REJECTED);
    }

    @Test
    @DisplayName("그사이 파일이 다시 교체됐으면 이전 버전 색인은 보내지 않는다")
    void skipsStaleVersion() {
        RuleDocumentIndexingOutbox outbox = givenIndexOutbox();
        ReflectionTestUtils.setField(document, "version", 2);

        worker.processDue();

        then(aiIndexingClient).should(never()).index(any());
        assertThat(outbox.getStatus()).isEqualTo(RuleDocumentIndexingOutboxStatus.DONE);
    }

    @Test
    @DisplayName("보내기 전에 삭제된 문서는 색인하지 않는다")
    void skipsDeletedDocument() {
        RuleDocumentIndexingOutbox outbox = givenIndexOutbox();
        document.delete();

        worker.processDue();

        then(aiIndexingClient).should(never()).index(any());
        assertThat(outbox.getStatus()).isEqualTo(RuleDocumentIndexingOutboxStatus.DONE);
    }

    @Test
    @DisplayName("색인 정리는 최신 유효 문서 목록만 보내고 정리 실패는 아웃박스에 실패로 남긴다")
    void sendsCleanupAndRecordsFailure() {
        RuleDocumentIndexingOutbox outbox = RuleDocumentIndexingOutbox.cleanup(BUILDING_ID, TRACE_ID,
            LocalDateTime.now().minusSeconds(1));
        givenOutbox(outbox);
        givenResponse(new Result(AiIndexingResponse.KIND_CLEANUP, null, "failed", "CLEANUP_FAILED"));

        worker.processDue();

        ArgumentCaptor<AiIndexingRequest> captor = ArgumentCaptor.forClass(AiIndexingRequest.class);
        then(aiIndexingClient).should().index(captor.capture());
        assertThat(captor.getValue().docId()).isNull();
        assertThat(captor.getValue().validDocIds()).containsExactly(DOC_ID, "99");
        assertThat(outbox.getStatus()).isEqualTo(RuleDocumentIndexingOutboxStatus.FAILED);
        assertThat(outbox.getLastError()).isEqualTo("CLEANUP_FAILED");
        then(documentRepository).should(never()).updateIndexingResult(anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("이미 처리된 아웃박스는 다시 보내지 않는다")
    void skipsAlreadyProcessedOutbox() {
        RuleDocumentIndexingOutbox outbox = givenIndexOutbox();
        outbox.markDone(LocalDateTime.now());

        worker.processDue();

        then(aiIndexingClient).should(never()).index(any());
    }

    private RuleDocumentIndexingOutbox givenIndexOutbox() {
        RuleDocumentIndexingOutbox outbox = RuleDocumentIndexingOutbox.index(document, TRACE_ID,
            LocalDateTime.now().minusSeconds(1));
        givenOutbox(outbox);
        return outbox;
    }

    private void givenOutbox(RuleDocumentIndexingOutbox outbox) {
        ReflectionTestUtils.setField(outbox, "id", OUTBOX_ID);
        given(outboxRepository.findDueIds(any(), any())).willReturn(List.of(OUTBOX_ID));
        given(outboxRepository.findById(OUTBOX_ID)).willReturn(Optional.of(outbox));
    }

    private void givenResponse(Result... results) {
        given(aiIndexingClient.index(any())).willReturn(new AiIndexingResponse(TRACE_ID, List.of(results)));
    }
}
