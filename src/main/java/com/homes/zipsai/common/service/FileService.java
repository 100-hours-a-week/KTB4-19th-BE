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
import com.homes.zipsai.common.dto.FileCompleteRequest;
import com.homes.zipsai.common.dto.FileCompleteResponse;
import com.homes.zipsai.common.dto.FileUploadRequest;
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

    private final FileRepository files;
    private final UserRepository users;
    private final S3StorageService storage;
    private final StorageProperties properties;

    @Transactional
    public FileUploadResponse createUpload(AuthPrincipal principal, FileUploadRequest request) {
        validateRequest(request);
        String extension = normalizeType(request.fileType());
        String key = "documents/rules/" + UUID.randomUUID() + "-" + safeName(request.originalName());
        File file = new File(key, request.fileSize(), extension, request.originalName());
        file.assignOwner(users.getReferenceById(principal.userId()));
        File saved = files.save(file);
        S3StorageService.PresignedUpload upload = storage.prepareUpload(
                key, contentType(extension), Duration.ofSeconds(properties.presignedUrlTtlSeconds()));
        return new FileUploadResponse(
                saved.getId(), upload.url(), properties.presignedUrlTtlSeconds(),
                Map.of("Content-Type", upload.contentType()));
    }

    @Transactional
    public FileCompleteResponse complete(AuthPrincipal principal, long attachmentId, FileCompleteRequest request) {
        if (!"UPLOADED".equals(request.fileStatus())) {
            throw new ValidationFailedException(
                    "fileStatus", ValidationFailedException.Reason.INVALID_FILE_TYPE);
        }
        File file = files.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.ATTACHMENT));
        if (file.getOwner() == null || !file.getOwner().getId().equals(principal.userId())) {
            throw new ForbiddenException();
        }
        if (file.getStatus() == FileStatus.UPLOADED) {
            return response(file);
        }
        S3StorageService.ObjectMetadata metadata = storage.head(file.getFileKey());
        if (metadata == null) {
            throw new ConflictException(ConflictException.Reason.UPLOAD_NOT_COMPLETED);
        }
        if (metadata.size() > properties.maxFileSize()) {
            throw PayloadTooLargeException.uploadedFileSizeExceeded();
        }
        String actualType = normalizeContentType(metadata.contentType(), file.getFileType());
        if (!ALLOWED_TYPES.contains(actualType)) {
            throw new ValidationFailedException("fileType", ValidationFailedException.Reason.INVALID_FILE_TYPE);
        }
        file.markUploaded((int) metadata.size(), actualType);
        return response(file);
    }

    private void validateRequest(FileUploadRequest request) {
        if (request.originalName().length() > 255) {
            throw new ValidationFailedException(
                    "originalName", ValidationFailedException.Reason.ORIGINAL_NAME_TOO_LONG);
        }
        String extension = normalizeType(request.fileType());
        if (!ALLOWED_TYPES.contains(extension)) {
            throw new ValidationFailedException("fileType", ValidationFailedException.Reason.INVALID_FILE_TYPE);
        }
        if (request.fileSize() > properties.maxFileSize()) {
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
