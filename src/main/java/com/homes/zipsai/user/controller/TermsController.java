package com.homes.zipsai.user.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.user.dto.response.TermsDetailResponse;
import com.homes.zipsai.user.dto.response.TermsListResponse;
import com.homes.zipsai.user.service.TermsService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "약관", description = "공개 약관 문서를 조회한다.")
@RestController
@RequestMapping("/api/v1/terms")
@RequiredArgsConstructor
public class TermsController {

    private final TermsService termsService;

    @Operation(summary = "약관 목록 조회", description = "현재 유효한 약관의 타입과 제목을 조회한다.")
    @GetMapping
    public ResponseEntity<ApiResponse<TermsListResponse>> getTermsList() {
        return ResponseEntity.ok(ApiResponse.data(termsService.getTermsList()));
    }

    @Operation(summary = "약관 상세 조회", description = "특정 타입의 현재 유효한 약관 본문을 조회한다.")
    @GetMapping("/{termsType}")
    public ResponseEntity<ApiResponse<TermsDetailResponse>> getTermsDetail(@PathVariable String termsType) {
        return ResponseEntity.ok(ApiResponse.data(termsService.getTermsDetail(termsType)));
    }
}
