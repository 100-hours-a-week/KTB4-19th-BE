package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.RuleDocument;
import com.homes.zipsai.building.domain.RuleDocumentIndexingOperation;
import com.homes.zipsai.building.domain.RuleDocumentIndexingOutbox;
import com.homes.zipsai.building.domain.RuleDocumentIndexingOutboxStatus;
import com.homes.zipsai.building.domain.RuleDocumentIndexingStatus;
import com.homes.zipsai.building.dto.RuleDocumentCreateRequest;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.RuleDocumentIndexingOutboxRepository;
import com.homes.zipsai.building.repository.RuleDocumentRepository;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.common.repository.FileRepository;
import com.homes.zipsai.common.service.PdfFixtures;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.user.domain.User;

@ExtendWith(MockitoExtension.class)
class RuleDocumentServiceIndexingTest {

    private static final Long MANAGER_ID = 1L;
    private static final long BUILDING_ID = 10L;
    private static final long DOCUMENT_ID = 100L;
    private static final String TRACE_ID = "request-trace-id";

    @Mock
    private RuleDocumentRepository documentRepository;

    @Mock
    private BuildingRepository buildingRepository;

    @Mock
    private FileRepository fileRepository;

    @Mock
    private S3StorageService s3StorageService;

    @Mock
    private RuleDocumentIndexingOutboxRepository outboxRepository;

    private RuleDocumentService ruleDocumentService;
    private Building building;
    private File file;

    @BeforeEach
    void setUp() {
        ruleDocumentService = new RuleDocumentService(documentRepository, buildingRepository, fileRepository,
            new StorageProperties(null, null, "test-bucket", 0, 0), s3StorageService, outboxRepository);

        User manager = new User("manager@zipsai.com", "password", "관리자", "01012345678");
        ReflectionTestUtils.setField(manager, "id", MANAGER_ID);
        building = new Building(manager, "서울시 강남구", "A타워");
        ReflectionTestUtils.setField(building, "id", BUILDING_ID);
        file = new File("documents/rule.pdf", 100, "application/pdf", "rule.pdf");
        ReflectionTestUtils.setField(file, "id", 1000L);
        file.assignOwner(manager);
        file.markUploaded(100, "application/pdf");

        given(buildingRepository.findByManager_IdAndDeletedAtIsNull(MANAGER_ID)).willReturn(Optional.of(building));
        MDC.put("traceId", TRACE_ID);
    }

    @AfterEach
    void tearDown() {
        MDC.remove("traceId");
    }

    @Test
    @DisplayName("문서를 올리면 AI를 직접 호출하지 않고 HTTP 요청 trace_id와 버전을 담아 색인을 아웃박스에 남긴다")
    void savesIndexingOutboxWhenDocumentCreated() {
        given(fileRepository.findById(1000L)).willReturn(Optional.of(file));
        given(documentRepository.save(any(RuleDocument.class))).willAnswer(invocation -> {
            RuleDocument saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", DOCUMENT_ID);
            return saved;
        });
        given(s3StorageService.read("documents/rule.pdf")).willReturn(PdfFixtures.plainPdf());
        givenDownloadUrl();

        ruleDocumentService.create(MANAGER_ID, new RuleDocumentCreateRequest(1000L, "관리규약"));

        RuleDocumentIndexingOutbox outbox = savedOutbox();
        assertThat(outbox.getOperation()).isEqualTo(RuleDocumentIndexingOperation.INDEX);
        assertThat(outbox.getBuildingId()).isEqualTo(BUILDING_ID);
        assertThat(outbox.getDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(outbox.getDocumentVersion()).isEqualTo(1);
        assertThat(outbox.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(outbox.getStatus()).isEqualTo(RuleDocumentIndexingOutboxStatus.PENDING);
    }

    @Test
    @DisplayName("문서 파일을 교체하면 색인 상태를 대기로 되돌리고 새 버전 색인을 아웃박스에 남긴다")
    void savesIndexingOutboxWhenDocumentUpdated() {
        RuleDocument document = savedDocument();
        ReflectionTestUtils.setField(document, "indexingStatus", RuleDocumentIndexingStatus.FAILED);
        ReflectionTestUtils.setField(document, "indexingError", "PARSE_FAILED");
        given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));
        File replacement = new File("documents/rule-v2.pdf", 100, "application/pdf", "rule-v2.pdf");
        ReflectionTestUtils.setField(replacement, "id", 2000L);
        replacement.assignOwner(building.getManager());
        replacement.markUploaded(100, "application/pdf");
        given(fileRepository.findById(2000L)).willReturn(Optional.of(replacement));
        given(s3StorageService.read("documents/rule-v2.pdf")).willReturn(PdfFixtures.plainPdf());
        givenDownloadUrl();

        var response = ruleDocumentService.update(MANAGER_ID, DOCUMENT_ID, "관리규약 개정", 2000L);

        assertThat(response.version()).isEqualTo(2);
        assertThat(document.getIndexingStatus()).isEqualTo(RuleDocumentIndexingStatus.PENDING);
        assertThat(document.getIndexingError()).isNull();
        RuleDocumentIndexingOutbox outbox = savedOutbox();
        assertThat(outbox.getDocumentVersion()).isEqualTo(2);
        assertThat(outbox.getTraceId()).isEqualTo(TRACE_ID);
    }

    @Test
    @DisplayName("문서를 삭제하면 HTTP 요청 trace_id를 담아 색인 정리를 아웃박스에 남긴다")
    void savesCleanupOutboxWhenDocumentDeleted() {
        RuleDocument document = savedDocument();
        given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));

        ruleDocumentService.delete(MANAGER_ID, DOCUMENT_ID);

        assertThat(document.isValid()).isFalse();
        RuleDocumentIndexingOutbox outbox = savedOutbox();
        assertThat(outbox.getOperation()).isEqualTo(RuleDocumentIndexingOperation.CLEANUP);
        assertThat(outbox.getBuildingId()).isEqualTo(BUILDING_ID);
        assertThat(outbox.getDocumentId()).isNull();
        assertThat(outbox.getTraceId()).isEqualTo(TRACE_ID);
    }

    private RuleDocument savedDocument() {
        RuleDocument document = new RuleDocument(building, file, "관리규약", "", 1);
        ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
        return document;
    }

    private void givenDownloadUrl() {
        given(s3StorageService.prepareDownload(any(), any()))
            .willReturn(new S3StorageService.PresignedDownload("https://s3.test/documents/rule.pdf"));
    }

    private RuleDocumentIndexingOutbox savedOutbox() {
        ArgumentCaptor<RuleDocumentIndexingOutbox> captor = ArgumentCaptor.forClass(RuleDocumentIndexingOutbox.class);
        then(outboxRepository).should().save(captor.capture());
        return captor.getValue();
    }
}
