package com.homes.zipsai.building.service;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.repository.ComplaintDetailRepository;
import com.homes.zipsai.conversation.ai.AiProcessingNotification;
import com.homes.zipsai.conversation.ai.AiProcessingNotification.Result;
import com.homes.zipsai.global.util.TimeUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ComplaintAiFeedbackResultService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComplaintAiFeedbackResultService.class);

    private final ComplaintDetailRepository complaintDetailRepository;

    public boolean supports(String kind) {
        return AiProcessingNotification.KIND_COMMENT_UPSERT.equals(kind)
                || AiProcessingNotification.KIND_COMMENT_DELETE.equals(kind);
    }

    @Transactional
    public void handle(AiProcessingNotification notification) {
        boolean upsert = AiProcessingNotification.KIND_COMMENT_UPSERT.equals(notification.kind());
        int failed = 0;
        int skipped = 0;
        int reverted = 0;
        for (Result result : notification.results()) {
            String status = result.status();
            if (AiProcessingNotification.STATUS_SUCCEEDED.equals(status)) {
                continue;
            }
            if (AiProcessingNotification.STATUS_SKIPPED.equals(status)) {
                skipped++;
                LOGGER.info("AI가 민원 답변 반영을 건너뛰었습니다. traceId={}, kind={}, complaintDetailId={}, reasonCode={}",
                        notification.traceId(), notification.kind(), result.complaintDetailId(), result.reasonCode());
                continue;
            }
            if (!AiProcessingNotification.STATUS_FAILED.equals(status)) {
                LOGGER.warn("알 수 없는 AI 처리 결과 상태입니다. traceId={}, kind={}, complaintDetailId={}, status={}",
                        notification.traceId(), notification.kind(), result.complaintDetailId(), status);
                continue;
            }
            failed++;
            LOGGER.warn("AI가 민원 답변 처리에 실패했습니다. traceId={}, kind={}, complaintDetailId={}, reasonCode={}",
                    notification.traceId(), notification.kind(), result.complaintDetailId(), result.reasonCode());
            reverted += upsert ? revertSent(notification, result) : revertDeleted(notification, result);
        }
        LOGGER.info("AI 민원 답변 처리 결과를 반영했습니다. traceId={}, kind={}, results={}, failed={}, skipped={}, reverted={}",
                notification.traceId(), notification.kind(), notification.results().size(), failed, skipped, reverted);
    }

    private int revertSent(AiProcessingNotification notification, Result result) {
        LocalDateTime version = toVersion(result);
        if (result.complaintDetailId() == null || version == null) {
            logInvalidResult(notification, result);
            return 0;
        }
        return complaintDetailRepository.revertAiFeedbackSent(result.complaintDetailId(), version);
    }

    private int revertDeleted(AiProcessingNotification notification, Result result) {
        LocalDateTime version = toVersion(result);
        if (result.complaintDetailId() == null || version == null) {
            logInvalidResult(notification, result);
            return 0;
        }
        return complaintDetailRepository.revertAiFeedbackDeleted(result.complaintDetailId(), version);
    }

    private static LocalDateTime toVersion(Result result) {
        return result.updatedAt() == null ? null : TimeUtils.toOffsetDateTime(result.updatedAt()).toLocalDateTime();
    }

    private static void logInvalidResult(AiProcessingNotification notification, Result result) {
        LOGGER.warn("AI 처리 실패 통지에 되돌릴 기준이 없어 반영하지 않았습니다. traceId={}, kind={}, complaintDetailId={}, "
                        + "updatedAt={}, reasonCode={}",
                notification.traceId(), notification.kind(), result.complaintDetailId(), result.updatedAt(),
                result.reasonCode());
    }
}
