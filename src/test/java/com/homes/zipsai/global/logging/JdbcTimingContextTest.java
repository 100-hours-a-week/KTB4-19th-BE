package com.homes.zipsai.global.logging;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionExecution;

class JdbcTimingContextTest {
    @AfterEach
    void clearRequestContext() {
        JdbcTimingContext.clear();
    }

    @Test
    @DisplayName("연결 획득 나노초를 밀리초로 내림 변환한다")
    void convertsConnectionAcquisitionNanosecondsToMillis() {
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        JdbcTimingContext.begin(structuredLogger, "trace-123", "/api/v1/test");

        JdbcTimingContext.recordConnectionAcquisition(999_999, false);
        JdbcTimingContext.recordConnectionAcquisition(1_500_000, false);

        verify(structuredLogger).stageDone(anyString(), anyString(), eq("mysql_connection_acquire"), eq(0L),
            eq("ok"), isNull());
        verify(structuredLogger).stageDone(anyString(), anyString(), eq("mysql_connection_acquire"), eq(1L),
            eq("ok"), isNull());
    }

    @Test
    @DisplayName("트랜잭션 경과 나노초를 밀리초로 내림 변환한다")
    void convertsTransactionNanosecondsToMillis() {
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        JdbcTimingContext.begin(structuredLogger, "trace-123", "/api/v1/test");
        TransactionExecution transaction = mock(TransactionExecution.class);
        given(transaction.isNewTransaction()).willReturn(true);
        JdbcTimingContext.beginTransaction(transaction, 1_000_000);

        JdbcTimingContext.recordTransactionBoundary(1_999_999, "commit", false);

        verify(structuredLogger).stageDone(anyString(), anyString(), eq("mysql_transaction_commit"), eq(0L),
            eq("ok"), isNull());
    }
}
