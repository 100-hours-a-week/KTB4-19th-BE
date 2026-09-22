package com.homes.zipsai.common.service;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.common.domain.FileStatus;
import com.homes.zipsai.common.dto.FileCompleteResponse;
import com.homes.zipsai.common.dto.FileDownloadResponse;
import com.homes.zipsai.common.dto.FileUploadResponse;
import com.homes.zipsai.common.repository.FileRepository;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.global.exception.NotFoundException;
import com.homes.zipsai.global.exception.PayloadTooLargeException;
import com.homes.zipsai.global.exception.ValidationFailedException;
import com.homes.zipsai.global.security.AuthPrincipal;
import com.homes.zipsai.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FileService {
    private static final Set<String> ALLOWED_TYPES = Set.of("jpg", "jpeg", "png", "heic", "pdf");

    private final FileRepository fileRepository;
    private final UserRepository userRepository;
    private final S3StorageService s3StorageService;
    private final StorageProperties storageProperties;

    @Transactional
    public FileUploadResponse createUpload(AuthPrincipal principal, String originalName, String fileType, int fileSize) {
        validateRequest(originalName, fileType, fileSize);
        String extension = normalizeType(fileType);
        String key = "documents/rules/" + UUID.randomUUID() + "-" + safeName(originalName);
        File file = new File(key, fileSize, extension, originalName);
        file.assignOwner(userRepository.getReferenceById(principal.userId()));
        File saved = fileRepository.save(file);
        S3StorageService.PresignedUpload upload = s3StorageService.prepareUpload(
                key, contentType(extension), Duration.ofSeconds(storageProperties.presignedUrlTtlSeconds()));
        return new FileUploadResponse(
                saved.getId(), upload.url(), storageProperties.presignedUrlTtlSeconds(),
                Map.of("Content-Type", upload.contentType()));
    }

    @Transactional
    public FileCompleteResponse complete(AuthPrincipal principal, long attachmentId, String fileStatus) {
        if (!"UPLOADED".equals(fileStatus)) {
            throw new ValidationFailedException(
                    "fileStatus", ValidationFailedException.Reason.INVALID_FILE_TYPE);
        }
        File file = fileRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.ATTACHMENT));
        if (file.getOwner() == null || !file.getOwner().getId().equals(principal.userId())) {
            throw new ForbiddenException();
        }
        if (file.getStatus() == FileStatus.UPLOADED) {
            return response(file);
        }
        S3StorageService.ObjectMetadata metadata = s3StorageService.head(file.getFileKey());
        if (metadata == null) {
            throw new ConflictException(ConflictException.Reason.UPLOAD_NOT_COMPLETED);
        }
        if (metadata.size() > storageProperties.maxFileSize()) {
            throw PayloadTooLargeException.uploadedFileSizeExceeded();
        }
        String actualType = normalizeContentType(metadata.contentType(), file.getFileType());
        if (!ALLOWED_TYPES.contains(actualType)) {
            throw new ValidationFailedException("fileType", ValidationFailedException.Reason.INVALID_FILE_TYPE);
        }
        file.markUploaded((int) metadata.size(), actualType);
        return response(file);
    }

    @Transactional(readOnly = true)
    public FileDownloadResponse createDownloadUrl(long attachmentId) {
        File file = fileRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.ATTACHMENT));
        if (file.getStatus() != FileStatus.UPLOADED) {
            throw new ConflictException(ConflictException.Reason.UPLOAD_NOT_COMPLETED);
        }
        int ttl = storageProperties.presignedUrlTtlSeconds();
        String url = s3StorageService.prepareDownload(file.getFileKey(), Duration.ofSeconds(ttl)).url();
        return new FileDownloadResponse(
                file.getId(), file.getOriginalName(), file.getFileSize(), file.getFileType(),
                file.getStatus().name(), url, ttl);
    }

    private void validateRequest(String originalName, String fileType, int fileSize) {
        if (originalName.length() > 255) {
            throw new ValidationFailedException(
                    "originalName", ValidationFailedException.Reason.ORIGINAL_NAME_TOO_LONG);
        }
        String extension = normalizeType(fileType);
        if (!ALLOWED_TYPES.contains(extension)) {
            throw new ValidationFailedException("fileType", ValidationFailedException.Reason.INVALID_FILE_TYPE);
        }
        if (fileSize > storageProperties.maxFileSize()) {
            throw PayloadTooLargeException.fileSizeLimitExceeded();
        }
    }

    private static String normalizeType(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).trim();
        if (normalized.startsWith("image/")) {
            return normalized.substring(6);
        }
        return normalized.equals("application/pdf") ? "pdf" : normalized;
    }

    private static String normalizeContentType(String contentType, String fallback) {
        if (contentType == null || contentType.isBlank()) {
            return fallback;
        }
        return normalizeType(contentType);
    }

    private static String contentType(String extension) {
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "heic" -> "image/heic";
            default -> "application/pdf";
        };
    }

    private static String safeName(String originalName) {
        String name = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    private static FileCompleteResponse response(File file) {
        return new FileCompleteResponse(
                file.getId(), file.getFileKey(), file.getOriginalName(), file.getFileSize(),
                file.getFileType(), file.getStatus().name());
    }
}
