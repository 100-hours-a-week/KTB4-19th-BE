package com.homes.zipsai.building.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ComplaintStatus {
    PENDING("처리전"),
    IN_PROGRESS("처리중"),
    DONE("처리완료");

    private final String label;
}
