package com.homes.zipsai.user.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.homes.zipsai.auth.dto.AgreementRequest;
import com.homes.zipsai.user.domain.UserRole;

@JsonIgnoreProperties(ignoreUnknown = false)
public record UserPatchRequest(
    String email,
    UserRole userRole,
    String userName,
    String phone,
    List<AgreementRequest> agreements
) {
}
