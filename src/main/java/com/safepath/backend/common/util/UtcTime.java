package com.safepath.backend.common.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * The V1 TIMESTAMP columns have no time zone and hold UTC. Values are cut to
 * microseconds, the precision PostgreSQL stores, so a saved value reads back equal.
 */
public final class UtcTime {
    private UtcTime() {}

    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public static LocalDateTime fromInstant(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    public static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
