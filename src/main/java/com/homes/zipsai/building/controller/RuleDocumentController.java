package com.homes.zipsai.building.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homes.zipsai.building.dto.RuleDocumentCreateRequest;
import com.homes.zipsai.building.dto.RuleDocumentResponse;
import com.homes.zipsai.building.service.RuleDocumentService;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/managers/me/documents")
@Tag(name = "관리자 문서", description = "관리자 건물 운영 문서 등록과 조회")
public class RuleDocumentController {
    private final RuleDocumentService ruleDocumentService;

    @PostMapping
    @Operation(summary = "운영 문서 등록", description = "업로드 완료된 첨부파일을 건물 문서로 등록하고 AI 색인을 요청한다.")
    public ResponseEntity<ApiResponse<RuleDocumentResponse>> create(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody RuleDocumentCreateRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.data(ruleDocumentService.create(principal, request)));
    }

    @GetMapping
    @Operation(summary = "운영 문서 목록 조회")
    public ApiResponse<List<RuleDocumentResponse>> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.data(ruleDocumentService.list(principal));
    }
}
