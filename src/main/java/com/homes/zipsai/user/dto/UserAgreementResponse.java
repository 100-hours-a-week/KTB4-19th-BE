package com.homes.zipsai.user.dto;

import java.time.LocalDateTime;

import com.homes.zipsai.user.domain.TermsType;

public record UserAgreementResponse(
        Long agreementId,
        TermsType termsType,
        boolean isAgreed,
        LocalDateTime agreedAt
) {
}
