package com.homes.zipsai.building.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.building.dto.request.ComplaintStatusUpdateRequest;
import com.homes.zipsai.building.dto.response.ComplaintDetailResponse;
import com.homes.zipsai.building.dto.response.ComplaintListResponse;
import com.homes.zipsai.building.dto.response.ComplaintStatusUpdateResponse;
import com.homes.zipsai.building.service.ComplaintService;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "관리자 민원", description = "관리자가 담당 건물의 민원을 조회한다.")
@RestController
@RequestMapping("/api/v1/managers/me/complaints")
@RequiredArgsConstructor
public class ManagerComplaintController {

    private final ComplaintService complaintService;

    @Operation(summary = "민원 처리 상태 변경",
        description = "관리자가 관리 대상 건물의 민원 처리 상태를 변경.")
    @PatchMapping("/{complaintId}")
    public ResponseEntity<ApiResponse<ComplaintStatusUpdateResponse>> updateComplaintStatus(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable @Positive(message = "1 이상의 정수여야 합니다.") Long complaintId,
            @Valid @RequestBody ComplaintStatusUpdateRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.data(
            complaintService.updateManagerComplaintStatus(principal.userId(), complaintId, request)));
    }

    @Operation(summary = "민원 상세 조회",
        description = "관리자가 관리 대상 건물의 민원을 상세 조회. ")
    @GetMapping("/{complaintId}")
    public ResponseEntity<ApiResponse<ComplaintDetailResponse>> getComplaint(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable @Positive(message = "1 이상의 정수여야 합니다.") Long complaintId
    ) {
        return ResponseEntity.ok(ApiResponse.data(
            complaintService.getManagerComplaint(principal.userId(), complaintId)));
    }

    @Operation(summary = "민원 목록 조회",
        description = "관리자가 관리 대상 건물의 민원 목록을 조회. 처리 상태, 긴급 여부에 대한 필터 지원.")
    @GetMapping
    public ResponseEntity<ApiResponse<ComplaintListResponse>> getComplaints(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String keyword,
            @RequestParam(name = "status", required = false) List<String> status,
            @RequestParam(defaultValue = "false") boolean urgentOnly,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "1 이상이어야 합니다.")
            @Max(value = 100, message = "100 이하여야 합니다.") int size
    ) {
        ComplaintListResponse response = complaintService.getManagerComplaints(
            principal.userId(), keyword, status, urgentOnly, page, size);
        return ResponseEntity.ok(ApiResponse.data(response));
    }
}
