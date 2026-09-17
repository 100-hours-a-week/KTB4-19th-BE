package com.homes.zipsai.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record SignupRequest(
    String email,
    String password,
    String passwordConfirm,
    String userName,
    String phone,
    List<AgreementRequest> agreements
) {
}
