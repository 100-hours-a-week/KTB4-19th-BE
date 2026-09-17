package com.homes.zipsai.building.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.building.dto.request.ComplaintCreateRequest;
import com.homes.zipsai.building.dto.response.ComplaintCreateResponse;
import com.homes.zipsai.building.service.ComplaintService;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "입주민 민원", description = "입주민이 AI 대화를 민원으로 접수한다.")
@RestController
@RequestMapping("/api/v1/residents/me/complaints")
@RequiredArgsConstructor
public class ResidentComplaintController {

    private final ComplaintService complaintService;

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
