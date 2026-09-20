package com.homes.zipsai.building.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.building.dto.request.ComplaintCreateRequest;
import com.homes.zipsai.building.dto.response.ComplaintCreateResponse;
import com.homes.zipsai.building.dto.response.ResidentComplaintDetailResponse;
import com.homes.zipsai.building.dto.response.ResidentComplaintListResponse;
import com.homes.zipsai.building.service.ComplaintService;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "입주민 민원", description = "입주민이 민원을 접수하고 조회한다.")
@RestController
@RequestMapping("/api/v1/residents/me/complaints")
@RequiredArgsConstructor
public class ResidentComplaintController {

    private final ComplaintService complaintService;

    @Operation(summary = "민원 목록 조회", description = "현재 거주 중인 입주민 본인의 민원 목록을 조회한다.")
    @GetMapping
    public ResponseEntity<ApiResponse<ResidentComplaintListResponse>> getComplaints(
        @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
        @Parameter(description = "민원 제목 검색어") @RequestParam(required = false) String keyword,
        @RequestParam(name = "status", required = false) List<String> status,
        @RequestParam(defaultValue = "0") @Min(value = 0, message = "0 이상이어야 합니다.") int page,
        @RequestParam(defaultValue = "20") @Min(value = 1, message = "1 이상이어야 합니다.")
        @Max(value = 100, message = "100 이하여야 합니다.") int size
    ) {
        return ResponseEntity.ok(ApiResponse.data(
            complaintService.getResidentComplaints(principal.userId(), keyword, status, page, size)));
    }

    @Operation(summary = "민원 상세 조회", description = "현재 거주 중인 입주민 본인의 민원 상세를 조회한다.")
    @GetMapping("/{complaintId}")
    public ResponseEntity<ApiResponse<ResidentComplaintDetailResponse>> getComplaint(
        @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
        @PathVariable @Positive(message = "1 이상의 정수여야 합니다.") Long complaintId
    ) {
        return ResponseEntity.ok(ApiResponse.data(
            complaintService.getResidentComplaint(principal.userId(), complaintId)));
    }

    @Operation(summary = "민원 접수 (대화 → 민원 전환)",
        description = "대화에서 수집한 위치, 시점, 증상으로 민원을 생성한다. 하나의 대화에서는 한 번만 접수할 수 있다.")
    @PostMapping
    public ResponseEntity<ApiResponse<ComplaintCreateResponse>> createComplaint(
        @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
        @Valid @RequestBody ComplaintCreateRequest request
    ) {
        ComplaintCreateResponse created = complaintService.createComplaint(principal.userId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.data(created));
    }
}
