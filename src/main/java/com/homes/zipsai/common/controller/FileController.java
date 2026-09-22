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
public class FileController {
    private final FileService fileService;

    @PostMapping
    public ResponseEntity<ApiResponse<FileUploadResponse>> createUpload(
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody FileUploadRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.data(fileService.createUpload(principal, request)));
    }

    @PatchMapping("/{attachmentId}")
    public ApiResponse<FileCompleteResponse> complete(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable long attachmentId,
            @Valid @RequestBody FileCompleteRequest request) {
        return ApiResponse.data(fileService.complete(principal, attachmentId, request));
    }

    @GetMapping("/{attachmentId}")
    public ApiResponse<FileDownloadResponse> createDownloadUrl(@PathVariable long attachmentId) {
        return ApiResponse.data(fileService.createDownloadUrl(attachmentId));
    }
}
