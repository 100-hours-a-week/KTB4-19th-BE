package com.homes.zipsai.migration.domain;

public enum OutboxDirection {
    V1_TO_V2,
    V2_TO_V1;

    public OutboxDirection reverse() {
        return this == V1_TO_V2 ? V2_TO_V1 : V1_TO_V2;
    }
}
