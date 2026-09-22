package com.homes.zipsai.common.controller;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.homes.zipsai.common.dto.FileCompleteRequest;
import com.homes.zipsai.common.dto.FileCompleteResponse;
import com.homes.zipsai.common.dto.FileDownloadResponse;
import com.homes.zipsai.common.dto.FileUploadRequest;
import com.homes.zipsai.common.dto.FileUploadResponse;
import com.homes.zipsai.common.service.FileService;
import com.homes.zipsai.global.response.ApiResponse;
import com.homes.zipsai.global.security.AuthPrincipal;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/files")
@Tag(name = "첨부파일", description = "S3 첨부파일 업로드와 조회용 URL을 관리한다.")
public class FileController {
    private final FileService fileService;

    @PostMapping
    @Operation(summary = "첨부파일 업로드 URL 발급", description = "S3에 직접 업로드할 수 있는 Presigned PUT URL을 발급한다.")
    public ResponseEntity<ApiResponse<FileUploadResponse>> createUpload(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody FileUploadRequest fileUploadRequest) {
        return ResponseEntity.status(201).body(ApiResponse.data(fileService.createUpload(
                principal, fileUploadRequest.originalName(), fileUploadRequest.fileType(), fileUploadRequest.fileSize())));
    }

    @PatchMapping("/{attachmentId}")
    @Operation(summary = "첨부파일 업로드 완료", description = "S3 업로드 결과를 확인하고 첨부파일 상태를 UPLOADED로 변경한다.")
    public ApiResponse<FileCompleteResponse> complete(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable long attachmentId,
            @Valid @RequestBody FileCompleteRequest fileCompleteRequest) {
        return ApiResponse.data(fileService.complete(principal, attachmentId, fileCompleteRequest.fileStatus()));
    }

    @GetMapping("/{attachmentId}")
    @Operation(summary = "첨부파일 조회 URL 발급", description = "짧은 시간 동안 유효한 Presigned GET URL을 발급한다.")
    public ApiResponse<FileDownloadResponse> createDownloadUrl(@PathVariable long attachmentId) {
        return ApiResponse.data(fileService.createDownloadUrl(attachmentId));
    }
}
