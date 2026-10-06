package com.homes.zipsai.global.logging;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

import org.springframework.transaction.TransactionExecution;

final class JdbcTimingContext {
    private static final ThreadLocal<Measurement> CURRENT = new ThreadLocal<>();

    private JdbcTimingContext() {
    }

    static void begin(StructuredLogger structuredLogger, String traceId, String route) {
        CURRENT.set(new Measurement(structuredLogger, traceId, route));
    }

    static Measurement finish() {
        Measurement measurement = CURRENT.get();
        CURRENT.remove();
        return measurement == null ? new Measurement(null, null, null) : measurement;
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

    static void recordConnectionAcquisition(long durationNanos, boolean failed) {
        try {
            Measurement measurement = CURRENT.get();
            if (measurement != null) {
                measurement.recordConnectionAcquisition(durationNanos, failed);
            }
        } catch (RuntimeException ignored) {

        }
    }

    static void beginTransaction(TransactionExecution transaction, long startedNanos) {
        try {
            Measurement measurement = CURRENT.get();
            if (measurement != null && transaction.isNewTransaction()) {
                measurement.beginTransaction(transaction, startedNanos);
            }
        } catch (RuntimeException ignored) {

        }
    }

    static void failTransactionBegin(TransactionExecution transaction, long endedNanos) {
        try {
            Measurement measurement = CURRENT.get();
            if (measurement != null) {
                measurement.finishTransaction(transaction, endedNanos, "begin", true);
            }
        } catch (RuntimeException ignored) {

        }
    }

    static void recordTransactionBoundary(long endedNanos, String boundary, boolean failed) {
        try {
            Measurement measurement = CURRENT.get();
            if (measurement != null) {
                measurement.finishCurrentTransaction(endedNanos, boundary, failed);
            }
        } catch (RuntimeException ignored) {

        }
    }

    static void clearTransaction(TransactionExecution transaction) {
        try {
            Measurement measurement = CURRENT.get();
            if (measurement != null) {
                measurement.removeTransaction(transaction);
            }
        } catch (RuntimeException ignored) {

        }
    }

    static final class Measurement {
        private final StructuredLogger structuredLogger;
        private final String traceId;
        private final String route;
        private final Deque<TransactionMeasurement> transactions = new ArrayDeque<>();
        private long durationNanos;
        private boolean failed;

        Measurement(StructuredLogger structuredLogger, String traceId, String route) {
            this.structuredLogger = structuredLogger;
            this.traceId = traceId;
            this.route = route;
        }

        void recordConnectionAcquisition(long duration, boolean callFailed) {
            if (structuredLogger != null) {
                structuredLogger.stageDone(traceId, route, "mysql_connection_acquire", duration / 1_000_000,
                    callFailed ? "fail" : "ok", null);
            }
        }

        void beginTransaction(TransactionExecution transaction, long startedNanos) {
            transactions.push(new TransactionMeasurement(transaction, startedNanos));
        }

        void finishTransaction(TransactionExecution transaction, long endedNanos, String boundary, boolean failed) {
            TransactionMeasurement measurement = removeTransaction(transaction);
            if (measurement != null) {
                logTransaction(measurement, endedNanos, boundary, failed);
            }
        }

        void finishCurrentTransaction(long endedNanos, String boundary, boolean failed) {
            TransactionMeasurement measurement = transactions.poll();
            if (measurement != null) {
                logTransaction(measurement, endedNanos, boundary, failed);
            }
        }

        private TransactionMeasurement removeTransaction(TransactionExecution transaction) {
            Iterator<TransactionMeasurement> iterator = transactions.iterator();
            while (iterator.hasNext()) {
                TransactionMeasurement measurement = iterator.next();
                if (measurement.transaction() == transaction) {
                    iterator.remove();
                    return measurement;
                }
            }
            return null;
        }

        private void logTransaction(TransactionMeasurement measurement, long endedNanos, String boundary,
                                    boolean failed) {
            if (structuredLogger != null) {
                long durationMillis = (endedNanos - measurement.startedNanos()) / 1_000_000;
                structuredLogger.stageDone(traceId, route, "mysql_transaction_" + boundary, durationMillis,
                    failed ? "fail" : "ok", null);
            }
        }

        void add(long duration, boolean callFailed) {
            durationNanos += duration;
            failed |= callFailed;
        }

        long durationNanos() {
            return durationNanos;
        }

        long durationMillis() {
            return durationNanos / 1_000_000;
        }

        String outcome() {
            return failed ? "fail" : "ok";
        }
    }

    private record TransactionMeasurement(TransactionExecution transaction, long startedNanos) {
    }
}
