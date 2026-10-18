package com.homes.zipsai.building.batch;

import java.util.Map;

import org.springframework.http.HttpStatus;

import com.homes.zipsai.global.exception.ApiException;

public class ComplaintAiFeedbackAlreadyRunningException extends ApiException {

    public ComplaintAiFeedbackAlreadyRunningException() {
        super(
                HttpStatus.CONFLICT,
                "민원 답변 AI 피드백 배치가 이미 실행 중입니다.",
                "AI_FEEDBACK_BATCH_ALREADY_RUNNING",
                Map.of("reason", "실행 중인 배치가 끝난 뒤 다시 시도해 주세요."));
    }
}
