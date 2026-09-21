package com.homes.zipsai.auth.dto;

import com.homes.zipsai.user.domain.TermsType;

public record AgreementRequest(TermsType termsType, Boolean isAgreed) {
}
