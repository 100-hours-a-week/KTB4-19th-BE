package com.homes.zipsai.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.common.repository.FileRepository;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class FileServiceDownloadAuthorizationTest {

    private static final AuthPrincipal OWNER_PRINCIPAL = new AuthPrincipal(1L, "session");
    private static final AuthPrincipal OTHER_PRINCIPAL = new AuthPrincipal(2L, "session");

    @Mock
    FileRepository fileRepository;

    @Mock
    UserRepository userRepository;

    @Mock
    S3StorageService s3StorageService;

    @Mock
    User owner;

    FileService fileService;
    File file;

    @BeforeEach
    void setUp() {
        fileService = new FileService(fileRepository, userRepository, s3StorageService,
            new StorageProperties(null, null, null, 300, 10 * 1024 * 1024));
        file = File.builder().fileKey("conversations/leak.jpg").fileSize(1024).fileType("jpg")
            .originalName("leak.jpg").build();
        file.assignOwner(owner);
        file.markUploaded(1024, "jpg");
        given(owner.getId()).willReturn(OWNER_PRINCIPAL.userId());
    }

    @Test
    @DisplayName("소유자가 아니면 다운로드 URL 발급을 거부한다")
    void rejectsDownloadByNonOwner() {
        given(fileRepository.findById(10L)).willReturn(Optional.of(file));

        assertThatThrownBy(() -> fileService.createDownloadUrl(OTHER_PRINCIPAL, 10L))
            .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("소유자는 다운로드 URL을 발급받을 수 있다")
    void allowsDownloadByOwner() {
        given(fileRepository.findById(10L)).willReturn(Optional.of(file));
        given(s3StorageService.prepareDownload(anyString(), any(Duration.class)))
            .willReturn(new S3StorageService.PresignedDownload("https://s3.test/conversations/leak.jpg"));

        var response = fileService.createDownloadUrl(OWNER_PRINCIPAL, 10L);

        assertThat(response.downloadUrl()).isEqualTo("https://s3.test/conversations/leak.jpg");
    }
}
