package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Map;
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
import com.homes.zipsai.building.dto.RuleDocumentCreateRequest;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.RuleDocumentRepository;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.common.repository.FileRepository;
import com.homes.zipsai.common.service.PdfFixtures;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.ai.AiIndexingClient;
import com.homes.zipsai.global.exception.ValidationFailedException;
import com.homes.zipsai.user.domain.User;

@ExtendWith(MockitoExtension.class)
class RuleDocumentServiceEncryptedPdfTest {

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

    private RuleDocumentService ruleDocumentService;
    private User manager;
    private Building building;

    @BeforeEach
    void setUp() {
        ruleDocumentService = new RuleDocumentService(documentRepository, buildingRepository, fileRepository,
            new StorageProperties(null, null, "test-bucket", 0, 0), s3StorageService, aiIndexingClientProvider);

        manager = new User("manager@zipsai.com", "password", "관리자", "01012345678");
        ReflectionTestUtils.setField(manager, "id", MANAGER_ID);
        building = new Building(manager, "서울시 강남구", "A타워");
        ReflectionTestUtils.setField(building, "id", BUILDING_ID);

        given(buildingRepository.findByManager_IdAndDeletedAtIsNull(MANAGER_ID)).willReturn(Optional.of(building));
    }

    @Test
    @DisplayName("열람 비밀번호가 걸린 PDF로 문서를 등록하면 거절하고 저장·색인하지 않는다")
    void rejectsUserPasswordPdfOnCreate() {
        given(fileRepository.findById(ATTACHMENT_ID))
            .willReturn(Optional.of(uploadedFile(ATTACHMENT_ID, "documents/locked.pdf", "pdf")));
        given(s3StorageService.read("documents/locked.pdf")).willReturn(PdfFixtures.userPasswordPdf());

        assertRejectedAsEncrypted(() -> ruleDocumentService.create(MANAGER_ID,
            new RuleDocumentCreateRequest(ATTACHMENT_ID, "관리규약")));

        then(documentRepository).should(never()).save(any());
        then(aiIndexingClientProvider).should(never()).getIfAvailable();
    }

    @Test
    @DisplayName("복사·인쇄 권한만 제한한 PDF로 문서를 등록해도 거절한다")
    void rejectsOwnerPasswordOnlyPdfOnCreate() {
        given(fileRepository.findById(ATTACHMENT_ID))
            .willReturn(Optional.of(uploadedFile(ATTACHMENT_ID, "documents/restricted.pdf", "pdf")));
        given(s3StorageService.read("documents/restricted.pdf")).willReturn(PdfFixtures.ownerPasswordOnlyPdf());

        assertRejectedAsEncrypted(() -> ruleDocumentService.create(MANAGER_ID,
            new RuleDocumentCreateRequest(ATTACHMENT_ID, "관리규약")));

        then(documentRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("이미지 파일은 PDF 검사 없이 등록한다")
    void skipsCheckForImages() {
        given(fileRepository.findById(ATTACHMENT_ID))
            .willReturn(Optional.of(uploadedFile(ATTACHMENT_ID, "documents/rule.png", "png")));
        given(documentRepository.save(any(RuleDocument.class))).willAnswer(invocation -> {
            RuleDocument saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", DOCUMENT_ID);
            return saved;
        });
        given(s3StorageService.prepareDownload(any(), any()))
            .willReturn(new S3StorageService.PresignedDownload("https://s3.test/documents/rule.png"));

        ruleDocumentService.create(MANAGER_ID, new RuleDocumentCreateRequest(ATTACHMENT_ID, "관리규약"));

        then(s3StorageService).should(never()).read(any());
        then(documentRepository).should().save(any(RuleDocument.class));
    }

    @Test
    @DisplayName("비밀번호가 걸린 PDF로 파일을 교체하면 거절하고 기존 파일과 버전을 유지한다")
    void rejectsEncryptedPdfOnReplace() {
        RuleDocument document = new RuleDocument(
            building, uploadedFile(ATTACHMENT_ID, "documents/rule.pdf", "pdf"), "관리규약", "", 1);
        ReflectionTestUtils.setField(document, "id", DOCUMENT_ID);
        given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));
        given(fileRepository.findById(REPLACEMENT_ID))
            .willReturn(Optional.of(uploadedFile(REPLACEMENT_ID, "documents/locked.pdf", "pdf")));
        given(s3StorageService.read("documents/locked.pdf")).willReturn(PdfFixtures.userPasswordPdf());

        assertRejectedAsEncrypted(() ->
            ruleDocumentService.update(MANAGER_ID, DOCUMENT_ID, "관리규약", REPLACEMENT_ID));

        assertThat(document.getAttachment().getId()).isEqualTo(ATTACHMENT_ID);
        assertThat(document.getVersion()).isEqualTo(1);
        then(aiIndexingClientProvider).should(never()).getIfAvailable();
    }

    private void assertRejectedAsEncrypted(Runnable action) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(ValidationFailedException.class, exception ->
                assertThat(exception.details.get("violations")).isEqualTo(List.of(Map.of(
                    "field", "attachmentId",
                    "reason", "비밀번호가 설정된 PDF는 등록할 수 없어요. 비밀번호를 해제한 뒤 다시 올려주세요."))));
    }

    private File uploadedFile(long id, String fileKey, String fileType) {
        File file = new File(fileKey, 100, fileType, "rule." + fileType);
        ReflectionTestUtils.setField(file, "id", id);
        file.assignOwner(manager);
        file.markUploaded(100, fileType);
        return file;
    }
}
