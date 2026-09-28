package com.homes.zipsai.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.common.domain.FilePurpose;
import com.homes.zipsai.common.repository.FileRepository;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class FileServiceKeyPrefixTest {

    private static final AuthPrincipal PRINCIPAL = new AuthPrincipal(1L, "session");

    @Mock
    FileRepository fileRepository;

    @Mock
    UserRepository userRepository;

    @Mock
    S3StorageService s3StorageService;

    @Mock
    User owner;

    FileService fileService;

    @BeforeEach
    void setUp() {
        fileService = new FileService(fileRepository, userRepository, s3StorageService,
            new StorageProperties(null, null, null, 300, 10 * 1024 * 1024));
        given(userRepository.getReferenceById(PRINCIPAL.userId())).willReturn(owner);
        given(fileRepository.save(any(File.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(s3StorageService.prepareUpload(anyString(), anyString(), any()))
            .willAnswer(invocation -> new S3StorageService.PresignedUpload(
                "https://s3.test/" + invocation.getArgument(0), invocation.getArgument(1)));
    }

    @Test
    @DisplayName("대화 사진은 conversations/ 경로에 저장한다")
    void storesConversationImageUnderConversationsPrefix() {
        fileService.createUpload(PRINCIPAL, "leak.jpg", "jpg", 1024, FilePurpose.CONVERSATION);

        assertThat(savedKey()).startsWith("conversations/");
    }

    @Test
    @DisplayName("규칙 문서는 documents/rules/ 경로에 저장한다")
    void storesRuleDocumentUnderDocumentsRulesPrefix() {
        fileService.createUpload(PRINCIPAL, "rule.pdf", "pdf", 1024, FilePurpose.RULE_DOCUMENT);

        assertThat(savedKey()).startsWith("documents/rules/");
    }

    @Test
    @DisplayName("용도가 달라도 같은 이름의 파일은 서로 다른 경로에 저장한다")
    void separatesKeysOfSameNameByPurpose() {
        fileService.createUpload(PRINCIPAL, "photo.jpg", "jpg", 1024, FilePurpose.CONVERSATION);
        String conversationKey = savedKey();

        fileService.createUpload(PRINCIPAL, "photo.jpg", "jpg", 1024, FilePurpose.RULE_DOCUMENT);

        assertThat(conversationKey).isNotEqualTo(savedKey());
    }

    @Test
    @DisplayName("S3 업로드 URL은 저장한 파일과 같은 키로 발급한다")
    void presignsUploadWithSavedKey() {
        fileService.createUpload(PRINCIPAL, "leak.jpg", "jpg", 1024, FilePurpose.CONVERSATION);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        then(s3StorageService).should()
            .prepareUpload(keyCaptor.capture(), anyString(), any(Duration.class));
        assertThat(keyCaptor.getValue()).isEqualTo(savedKey());
    }

    private String savedKey() {
        ArgumentCaptor<File> fileCaptor = ArgumentCaptor.forClass(File.class);
        then(fileRepository).should(atLeastOnce()).save(fileCaptor.capture());
        return fileCaptor.getValue().getFileKey();
    }
}
