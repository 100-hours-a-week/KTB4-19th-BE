package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.List;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.RuleDocument;
import com.homes.zipsai.building.dto.RuleDocumentCreateRequest;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.RuleDocumentRepository;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.common.repository.FileRepository;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.ai.AiIndexingClient;
import com.homes.zipsai.conversation.ai.AiIndexingRequest;
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
    private ObjectProvider<AiIndexingClient> aiIndexingClientProvider;

    @Mock
    private AiIndexingClient aiIndexingClient;

    private RuleDocumentService ruleDocumentService;
    private Building building;
    private File file;

    @BeforeEach
    void setUp() {
        ruleDocumentService = new RuleDocumentService(documentRepository, buildingRepository, fileRepository,
            new StorageProperties(null, null, "test-bucket", 0, 0), s3StorageService, aiIndexingClientProvider);

        User manager = new User("manager@zipsai.com", "password", "관리자", "01012345678");
        ReflectionTestUtils.setField(manager, "id", MANAGER_ID);
        building = new Building(manager, "서울시 강남구", "A타워");
        ReflectionTestUtils.setField(building, "id", BUILDING_ID);
        file = new File("documents/rule.pdf", 100, "application/pdf", "rule.pdf");
        ReflectionTestUtils.setField(file, "id", 1000L);
        file.assignOwner(manager);
        file.markUploaded(100, "application/pdf");

        given(buildingRepository.findByManager_IdAndDeletedAtIsNull(MANAGER_ID)).willReturn(Optional.of(building));
        given(aiIndexingClientProvider.getIfAvailable()).willReturn(aiIndexingClient);
        MDC.put("traceId", TRACE_ID);
    }

    @AfterEach
    void tearDown() {
        MDC.remove("traceId");
    }

    @Test
    @DisplayName("문서를 올리면 AI 색인 요청에 HTTP 요청 trace_id를 담는다")
    void sendsTraceIdWhenDocumentCreated() {
        given(fileRepository.findById(1000L)).willReturn(Optional.of(file));
        given(documentRepository.save(any(RuleDocument.class))).willAnswer(invocation -> {
            RuleDocument saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", DOCUMENT_ID);
            return saved;
        });
        givenDownloadUrl();

        ruleDocumentService.create(MANAGER_ID, new RuleDocumentCreateRequest(1000L, "관리규약"));

        assertThat(sentRequest().traceId()).isEqualTo(TRACE_ID);
    }

    @Test
    @DisplayName("문서 파일을 교체하면 AI 색인 요청에 HTTP 요청 trace_id를 담는다")
    void sendsTraceIdWhenDocumentUpdated() {
        given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(savedDocument()));
        File replacement = new File("documents/rule-v2.pdf", 100, "application/pdf", "rule-v2.pdf");
        ReflectionTestUtils.setField(replacement, "id", 2000L);
        replacement.assignOwner(building.getManager());
        replacement.markUploaded(100, "application/pdf");
        given(fileRepository.findById(2000L)).willReturn(Optional.of(replacement));
        givenDownloadUrl();

        ruleDocumentService.update(MANAGER_ID, DOCUMENT_ID, "관리규약", 2000L);

        assertThat(sentRequest().traceId()).isEqualTo(TRACE_ID);
    }

    @Test
    @DisplayName("문서를 삭제하면 AI 색인 정리 요청에 HTTP 요청 trace_id를 담는다")
    void sendsTraceIdWhenDocumentDeleted() {
        given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(savedDocument()));
        given(documentRepository.findAllByBuilding_IdAndValidTrueAndDeletedAtIsNullOrderByUpdatedAtDesc(BUILDING_ID))
            .willReturn(List.of());

        ruleDocumentService.delete(MANAGER_ID, DOCUMENT_ID);

        then(aiIndexingClient).should().cleanup(BUILDING_ID, TRACE_ID, List.of());
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

    private AiIndexingRequest sentRequest() {
        ArgumentCaptor<AiIndexingRequest> captor = ArgumentCaptor.forClass(AiIndexingRequest.class);
        then(aiIndexingClient).should().index(captor.capture());
        return captor.getValue();
    }
}
