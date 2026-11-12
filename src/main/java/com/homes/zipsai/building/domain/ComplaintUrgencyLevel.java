package com.homes.zipsai.building.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ComplaintUrgencyLevel {
    NORMAL(0, 39),
    PRIORITY(40, 79),
    URGENT(80, 100);

    private final int minScore;
    private final int maxScore;

    public static ComplaintUrgencyLevel fromScore(Integer score) {
        if (score == null || score < NORMAL.minScore || score > URGENT.maxScore) {
            return NORMAL;
        }
        if (score >= URGENT.minScore) {
            return URGENT;
        }
        if (score >= PRIORITY.minScore) {
            return PRIORITY;
        }
        return NORMAL;
    }
}
