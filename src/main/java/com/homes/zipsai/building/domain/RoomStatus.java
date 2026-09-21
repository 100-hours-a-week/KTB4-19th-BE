package com.homes.zipsai.building.domain;

public enum RoomStatus {
    EMPTY("공실"),
    INVITED("초대됨"),
    LIVING("거주 중");

    private final String label;

    RoomStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
