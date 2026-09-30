package com.homes.zipsai.global.logging;

final class JdbcTimingContext {
    private static final ThreadLocal<Measurement> CURRENT = new ThreadLocal<>();

    private JdbcTimingContext() {
    }

    static void begin() {
        CURRENT.set(new Measurement());
    }

    static Measurement finish() {
        Measurement measurement = CURRENT.get();
        CURRENT.remove();
        return measurement == null ? new Measurement() : measurement;
    }

    static void clear() {
        CURRENT.remove();
    }

    static void record(long durationNanos, boolean failed) {
        try {
            Measurement measurement = CURRENT.get();
            if (measurement != null) {
                measurement.add(durationNanos, failed);
            }
        } catch (RuntimeException ignored) {

        }
    }

    static void recordFailure() {
        record(0, true);
    }

    static final class Measurement {
        private long durationNanos;
        private boolean failed;

        synchronized void add(long duration, boolean callFailed) {
            durationNanos += duration;
            failed |= callFailed;
        }

        synchronized long durationNanos() {
            return durationNanos;
        }

        synchronized long durationMillis() {
            return durationNanos / 1_000_000;
        }

        synchronized String outcome() {
            return failed ? "fail" : "ok";
        }
    }
}
