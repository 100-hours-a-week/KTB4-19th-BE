package com.homes.zipsai.building.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.RuleDocument;
import com.homes.zipsai.building.dto.RuleDocumentCreateRequest;
import com.homes.zipsai.building.dto.RuleDocumentResponse;
import com.homes.zipsai.building.dto.RuleDocumentUpdateRequest;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.RuleDocumentRepository;
import com.homes.zipsai.common.config.StorageProperties;
import com.homes.zipsai.common.domain.File;
import com.homes.zipsai.common.domain.FileStatus;
import com.homes.zipsai.common.repository.FileRepository;
import com.homes.zipsai.common.service.S3StorageService;
import com.homes.zipsai.conversation.ai.AiIndexingClient;
import com.homes.zipsai.conversation.ai.AiIndexingRequest;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.global.exception.NotFoundException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RuleDocumentService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuleDocumentService.class);

    private final RuleDocumentRepository documentRepository;
    private final BuildingRepository buildingRepository;
    private final FileRepository fileRepository;
    private final StorageProperties storageProperties;
    private final S3StorageService s3StorageService;
    private final ObjectProvider<AiIndexingClient> aiIndexingClient;

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
        RuleDocument saved = documentRepository.save(new RuleDocument(
                building, file, request.title(), "", 1));
        index(saved, building);
        return response(saved);
    }

    @Transactional(readOnly = true)
    public List<RuleDocumentResponse> list(Long userId) {
        Building building = buildingRepository.findByManager_IdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.BUILDING));
        return documentRepository.findAllByBuilding_IdAndDeletedAtIsNullOrderByUpdatedAtDesc(building.getId())
                .stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public RuleDocumentResponse get(Long userId, long documentId) {
        Building building = buildingRepository.findByManager_IdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.BUILDING));
        RuleDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.DOCUMENT));
        if (!document.getBuilding().getId().equals(building.getId())) {
            throw new ForbiddenException();
        }
        return response(document);
    }

    @Transactional
    public RuleDocumentResponse update(Long userId, long documentId, RuleDocumentUpdateRequest request) {
        Building building = buildingRepository.findByManager_IdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.BUILDING));
        RuleDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.DOCUMENT));
        if (!document.getBuilding().getId().equals(building.getId())) {
            throw new ForbiddenException();
        }
        document.updateTitle(request.title().trim());
        index(document, building);
        return response(document);
    }

    private void index(RuleDocument document, Building building) {
        try {
            String fileKey = "s3://" + storageProperties.uploadBucket() + "/" + document.getAttachment().getFileKey();
            List<String> validDocumentIds = documentRepository
                    .findAllByBuilding_IdAndDeletedAtIsNullOrderByUpdatedAtDesc(building.getId())
                    .stream().map(item -> String.valueOf(item.getId())).toList();
            AiIndexingClient client = aiIndexingClient.getIfAvailable();
            if (client == null) {
                LOGGER.error("AI 색인 클라이언트가 설정되지 않았습니다. buildingId={}, docId={}",
                        building.getId(), document.getId());
                return;
            }
            client.index(new AiIndexingRequest(
                    building.getId(), String.valueOf(document.getId()), document.getTitle(), fileKey,
                    validDocumentIds));
        } catch (RuntimeException exception) {
            LOGGER.warn("문서 저장 후 AI 색인 요청에 실패했습니다. documentId={}", document.getId(), exception);
        }
    }

    private RuleDocumentResponse response(RuleDocument document) {
        return new RuleDocumentResponse(document.getId(), document.getAttachment().getId(), document.getTitle(),
                document.getVersion(), document.getUpdatedAt(),
                s3StorageService.prepareDownload(document.getAttachment().getFileKey(),
                        java.time.Duration.ofSeconds(storageProperties.presignedUrlTtlSeconds())).url(),
                document.getAttachment().getOriginalName());
    }
}
