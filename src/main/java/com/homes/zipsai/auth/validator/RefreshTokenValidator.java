package com.homes.zipsai.auth.validator;

import com.homes.zipsai.global.exception.UnauthorizedException;

public final class RefreshTokenValidator {
    private static final int MAX_LENGTH = 100;

    private RefreshTokenValidator() {
    }

    public static String validate(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_LENGTH) {
            throw new UnauthorizedException();
        }
        return token;
    }
}
