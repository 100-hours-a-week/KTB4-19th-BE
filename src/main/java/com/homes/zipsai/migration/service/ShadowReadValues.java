package com.homes.zipsai.migration.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

import com.homes.zipsai.global.util.TextUtils;

final class ShadowReadValues {

    private static final Pattern PLAIN_VALUE_FIELD = Pattern.compile(
        ".*(Id|At|Code|Status|Type|Label|Count)|.*_(id|at|code|status|type|count)|hasNext|present");
    private static final String HASH_PREFIX = "sha256:";
    private static final int HASH_LENGTH = 16;
    private static final int VALUE_MAX_LENGTH = 100;

    private ShadowReadValues() {
    }

    static String mask(String field, String plainText, String hashSource) {
        if (hashSource == null) {
            return null;
        }
        if (plainText != null && PLAIN_VALUE_FIELD.matcher(field).matches()) {
            return TextUtils.truncate(plainText, VALUE_MAX_LENGTH);
        }
        return hash(hashSource);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HASH_PREFIX + HexFormat.of().formatHex(digest).substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
