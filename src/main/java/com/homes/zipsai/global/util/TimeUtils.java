package com.homes.zipsai.global.util;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public final class TimeUtils {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private TimeUtils() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(SEOUL);
    }

    public static OffsetDateTime toOffsetDateTime(LocalDateTime time) {
        return time == null ? null : time.atZone(SEOUL).toOffsetDateTime();
    }

    public static OffsetDateTime toOffsetDateTime(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(SEOUL).toOffsetDateTime();
    }
}
