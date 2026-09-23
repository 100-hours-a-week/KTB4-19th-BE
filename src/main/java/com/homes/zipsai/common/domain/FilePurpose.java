package com.homes.zipsai.common.domain;

public enum FilePurpose {
    CONVERSATION("conversations/"),
    RULE_DOCUMENT("documents/rules/");

    private final String keyPrefix;

    FilePurpose(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String keyPrefix() {
        return keyPrefix;
    }
}
