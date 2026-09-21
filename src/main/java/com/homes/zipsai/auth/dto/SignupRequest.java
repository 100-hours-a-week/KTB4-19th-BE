package com.homes.zipsai.auth.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
