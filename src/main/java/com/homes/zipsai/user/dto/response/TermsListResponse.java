package com.homes.zipsai.user.dto.response;

import java.util.List;

import com.homes.zipsai.user.domain.TermsType;

public record TermsListResponse(List<TermsItem> terms) {

    public record TermsItem(TermsType termsType, String title) {
    }
}
