package com.homes.zipsai.building.controller;

import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.building.batch.ComplaintAiFeedbackJobRunner;
import com.homes.zipsai.building.dto.response.ComplaintAiFeedbackRunResponse;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "민원 답변 AI 피드백 배치", description = "허용된 관리자가 민원 답변 AI 피드백 배치를 수동으로 실행한다.")
@RestController
@RequestMapping("/api/v1/managers/complaint-ai-feedback")
public class ComplaintAiFeedbackBatchController {

    private final ComplaintAiFeedbackJobRunner jobRunner;
    private final Set<Long> operatorIds;

    public ComplaintAiFeedbackBatchController(
            ComplaintAiFeedbackJobRunner jobRunner,
            @Value("${app.complaint.ai-feedback.operator-ids:}") List<Long> operatorIds) {
        this.jobRunner = jobRunner;
        this.operatorIds = Set.copyOf(operatorIds);
    }

    @Operation(summary = "민원 답변 AI 피드백 전송 수동 실행",
        description = "전송 대상 답변을 AI에 보내는 배치를 백그라운드에서 실행. 허용된 관리자만 호출 가능.")
    @PostMapping("/send")
    public ResponseEntity<ApiResponse<ComplaintAiFeedbackRunResponse>> send(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
    ) {
        verifyOperator(principal);
        String runId = jobRunner.startSend(principal.userId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.data(new ComplaintAiFeedbackRunResponse(runId, "SEND")));
    }

    @Operation(summary = "민원 답변 AI 피드백 삭제 수동 실행",
        description = "삭제된 답변을 AI에서 제거하는 배치를 백그라운드에서 실행. 허용된 관리자만 호출 가능.")
    @PostMapping("/delete")
    public ResponseEntity<ApiResponse<ComplaintAiFeedbackRunResponse>> delete(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal
    ) {
        verifyOperator(principal);
        String runId = jobRunner.startDelete(principal.userId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ApiResponse.data(new ComplaintAiFeedbackRunResponse(runId, "DELETE")));
    }

    private void verifyOperator(AuthPrincipal principal) {
        if (!operatorIds.contains(principal.userId())) {
            throw new ForbiddenException();
        }
    }
}
