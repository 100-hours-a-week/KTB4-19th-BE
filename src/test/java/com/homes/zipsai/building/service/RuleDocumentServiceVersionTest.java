package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.RuleDocument;
import com.homes.zipsai.building.dto.RuleDocumentResponse;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.RuleDocumentRepository;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.common.repository.FileRepository;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.ai.AiIndexingClient;
import com.homes.zipsai.user.domain.User;

@ExtendWith(MockitoExtension.class)
class RuleDocumentServiceVersionTest {

    private static final Long MANAGER_ID = 1L;
    private static final long BUILDING_ID = 10L;
    private static final long DOCUMENT_ID = 100L;
    private static final long ATTACHMENT_ID = 1000L;
    private static final long REPLACEMENT_ID = 2000L;

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
    private User manager;
    private RuleDocument document;

    @BeforeEach
    void setUp() {
        ruleDocumentService = new RuleDocumentService(documentRepository, buildingRepository, fileRepository,
            new StorageProperties(null, null, "test-bucket", 0, 0), s3StorageService, aiIndexingClientProvider);

        manager = new User("manager@zipsai.com", "password", "관리자", "01012345678");
        ReflectionTestUtils.setField(manager, "id", MANAGER_ID);
        Building building = new Building(manager, "서울시 강남구", "A타워");
        ReflectionTestUtils.setField(building, "id", BUILDING_ID);
        document = new RuleDocument(building, uploadedFile(ATTACHMENT_ID, "documents/rule.pdf"), "관리규약", "", 1);
        ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);

        given(buildingRepository.findByManager_IdAndDeletedAtIsNull(MANAGER_ID)).willReturn(Optional.of(building));
        given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));
        given(aiIndexingClientProvider.getIfAvailable()).willReturn(aiIndexingClient);
        given(s3StorageService.prepareDownload(any(), any()))
            .willReturn(new S3StorageService.PresignedDownload("https://s3.test/documents/rule.pdf"));
    }

    @Test
    @DisplayName("제목만 수정하면 버전이 올라가지 않는다")
    void keepsVersionWhenOnlyTitleUpdated() {
        RuleDocumentResponse response = ruleDocumentService.update(MANAGER_ID, DOCUMENT_ID, "관리규약 개정", null);

        assertThat(response.title()).isEqualTo("관리규약 개정");
        assertThat(response.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 파일로 저장하면 버전이 올라가지 않는다")
    void keepsVersionWhenSameAttachmentSaved() {
        RuleDocumentResponse response = ruleDocumentService.update(MANAGER_ID, DOCUMENT_ID, "관리규약", ATTACHMENT_ID);

        assertThat(response.attachmentId()).isEqualTo(ATTACHMENT_ID);
        assertThat(response.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 파일로 교체하면 버전이 1 올라간다")
    void increasesVersionWhenAttachmentReplaced() {
        given(fileRepository.findById(REPLACEMENT_ID))
            .willReturn(Optional.of(uploadedFile(REPLACEMENT_ID, "documents/rule-v2.pdf")));

        RuleDocumentResponse response = ruleDocumentService.update(MANAGER_ID, DOCUMENT_ID, "관리규약", REPLACEMENT_ID);

        assertThat(response.attachmentId()).isEqualTo(REPLACEMENT_ID);
        assertThat(response.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("제목과 파일을 함께 바꿔도 버전은 1만 올라간다")
    void increasesVersionOnceWhenTitleAndAttachmentUpdated() {
        given(fileRepository.findById(REPLACEMENT_ID))
            .willReturn(Optional.of(uploadedFile(REPLACEMENT_ID, "documents/rule-v2.pdf")));

        RuleDocumentResponse response =
            ruleDocumentService.update(MANAGER_ID, DOCUMENT_ID, "관리규약 개정", REPLACEMENT_ID);

        assertThat(response.title()).isEqualTo("관리규약 개정");
        assertThat(response.version()).isEqualTo(2);
    }

    private File uploadedFile(long id, String fileKey) {
        File file = new File(fileKey, 100, "application/pdf", "rule.pdf");
        ReflectionTestUtils.setField(file, "id", id);
        file.assignOwner(manager);
        file.markUploaded(100, "application/pdf");
        return file;
    }
}
