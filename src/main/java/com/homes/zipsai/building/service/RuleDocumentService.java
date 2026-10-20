package com.homes.zipsai.building.service;

import java.util.List;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.RuleDocument;
import com.homes.zipsai.building.domain.RuleDocumentIndexingOutbox;
import com.homes.zipsai.building.dto.RuleDocumentCreateRequest;
import com.homes.zipsai.building.dto.RuleDocumentResponse;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.RuleDocumentIndexingOutboxRepository;
import com.homes.zipsai.building.repository.RuleDocumentRepository;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.common.domain.FileStatus;
import com.homes.zipsai.common.repository.FileRepository;
import com.homes.zipsai.common.service.PdfInspector;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.global.exception.NotFoundException;
import com.homes.zipsai.global.exception.ValidationFailedException;
import com.homes.zipsai.global.util.TimeUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RuleDocumentService {
    private final RuleDocumentRepository documentRepository;
    private final BuildingRepository buildingRepository;
    private final FileRepository fileRepository;
    private final StorageProperties storageProperties;
    private final S3StorageService s3StorageService;
    private final RuleDocumentIndexingOutboxRepository outboxRepository;

    @Transactional
    public RuleDocumentResponse create(Long userId, RuleDocumentCreateRequest request) {
        Building building = buildingRepository.findByManager_IdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.BUILDING));
        File file = fileRepository.findById(request.attachmentId())
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.ATTACHMENT));
        if (file.getOwner() == null || !file.getOwner().getId().equals(userId)) {
            throw new ForbiddenException();
        }
        if (file.getStatus() != FileStatus.UPLOADED) {
            throw new ConflictException(ConflictException.Reason.UPLOAD_NOT_COMPLETED);
        }
        rejectEncryptedPdf(file);
        RuleDocument saved = documentRepository.save(new RuleDocument(
                building, file, request.title(), "", 1));
        index(saved);
        return response(saved);
    }

    @Transactional(readOnly = true)
    public List<RuleDocumentResponse> list(Long userId) {
        Building building = buildingRepository.findByManager_IdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.BUILDING));
        return documentRepository.findAllByBuilding_IdAndValidTrueAndDeletedAtIsNullOrderByUpdatedAtDesc(
                building.getId())
                .stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public RuleDocumentResponse get(Long userId, long documentId) {
        Building building = buildingRepository.findByManager_IdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.BUILDING));
        RuleDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.DOCUMENT));
        if (!document.isValid() || !document.getBuilding().getId().equals(building.getId())) {
            throw new ForbiddenException();
        }
        return response(document);
    }

    @Transactional
    public RuleDocumentResponse update(Long userId, long documentId, String title, Long attachmentId) {
        Building building = buildingRepository.findByManager_IdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.BUILDING));
        RuleDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.DOCUMENT));
        if (!document.isValid() || !document.getBuilding().getId().equals(building.getId())) {
            throw new ForbiddenException();
        }
        String trimmedTitle = title.trim();
        if (!trimmedTitle.equals(document.getTitle())) {
            document.updateTitle(trimmedTitle);
        }

        if (attachmentId != null && !attachmentId.equals(document.getAttachment().getId())) {
            File replacement = fileRepository.findById(attachmentId)
                    .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.ATTACHMENT));
            if (replacement.getOwner() == null || !replacement.getOwner().getId().equals(userId)) {
                throw new ForbiddenException();
            }
            if (replacement.getStatus() != FileStatus.UPLOADED) {
                throw new ConflictException(ConflictException.Reason.UPLOAD_NOT_COMPLETED);
            }
            rejectEncryptedPdf(replacement);
            document.replaceAttachment(replacement);
            index(document);
        }
        return response(document);
    }

    @Transactional
    public void delete(Long userId, long documentId) {
        Building building = buildingRepository.findByManager_IdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.BUILDING));
        RuleDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.DOCUMENT));
        if (!document.getBuilding().getId().equals(building.getId())) {
            throw new ForbiddenException();
        }
        document.delete();
        indexCleanup(building);
    }

    private void rejectEncryptedPdf(File file) {
        String fileType = file.getFileType();
        if (!"pdf".equals(fileType) && !"application/pdf".equals(fileType)) {
            return;
        }
        if (PdfInspector.isEncrypted(s3StorageService.read(file.getFileKey()))) {
            throw new ValidationFailedException("attachmentId", ValidationFailedException.Reason.ENCRYPTED_PDF);
        }
    }

    private void index(RuleDocument document) {
        outboxRepository.save(RuleDocumentIndexingOutbox.index(document, MDC.get("traceId"), TimeUtils.now()));
    }

    private void indexCleanup(Building building) {
        outboxRepository.save(RuleDocumentIndexingOutbox.cleanup(building.getId(), MDC.get("traceId"),
                TimeUtils.now()));
    }

    private RuleDocumentResponse response(RuleDocument document) {
        return new RuleDocumentResponse(document.getId(), document.getAttachment().getId(), document.getTitle(),
                document.getVersion(), document.getUpdatedAt(),
                s3StorageService.prepareDownload(document.getAttachment().getFileKey(),
                        java.time.Duration.ofSeconds(storageProperties.presignedUrlTtlSeconds())).url(),
                document.getAttachment().getOriginalName());
    }
}
