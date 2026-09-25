package com.homes.zipsai.auth.dto.request;

import jakarta.validation.constraints.NotNull;

import com.homes.zipsai.user.domain.TermsType;

public record AgreementRequest(
        @NotNull TermsType termsType,
        @NotNull(message = "동의 여부는 boolean이어야 합니다.") Boolean isAgreed
) {
}
