package com.homes.zipsai.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionExecution;

class JdbcTransactionTimingListenerTest {
    @AfterEach
    void clearRequestContext() {
        JdbcTimingContext.clear();
    }

    @Test
    @DisplayName("JDBC commit 반환 시각에 기록하고 callback 지연은 포함하지 않는다")
    void recordsCommitAtJdbcBoundaryBeforeTransactionCallback() throws Exception {
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        AtomicBoolean callbackInvoked = new AtomicBoolean();
        doAnswer(invocation -> {
            if ("mysql_transaction_commit".equals(invocation.getArgument(2))) {
                assertThat(callbackInvoked).isFalse();
            }
            return null;
        }).when(structuredLogger).stageDone(anyString(), anyString(), anyString(), anyLong(), anyString(), isNull());
        JdbcTimingContext.begin(structuredLogger, "trace-123", "/api/v1/test");
        JdbcTransactionTimingListener listener = new JdbcTransactionTimingListener();
        TransactionExecution transaction = newTransaction();
        listener.beforeBegin(transaction);
        Connection connection = timingDataSource(mock(Connection.class)).getConnection();

        connection.commit();
        callbackInvoked.set(true);
        listener.afterCommit(transaction, null);

        verify(structuredLogger).stageDone(eq("trace-123"), eq("/api/v1/test"), eq("mysql_transaction_commit"),
            anyLong(), eq("ok"), isNull());
    }

    @Test
    @DisplayName("NESTED savepoint rollback은 바깥 물리 트랜잭션을 종료하지 않는다")
    void keepsOuterTransactionOpenAfterSavepointRollback() throws Exception {
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        JdbcTimingContext.begin(structuredLogger, "trace-123", "/api/v1/test");
        JdbcTransactionTimingListener listener = new JdbcTransactionTimingListener();
        TransactionExecution outer = newTransaction();
        TransactionExecution nested = mock(TransactionExecution.class);
        given(nested.isNewTransaction()).willReturn(false);
        listener.beforeBegin(outer);
        listener.beforeBegin(nested);
        Connection connection = timingDataSource(mock(Connection.class)).getConnection();

        connection.rollback(mock(Savepoint.class));
        listener.afterRollback(nested, null);
        connection.commit();
        listener.afterCommit(outer, null);

        verify(structuredLogger).stageDone(anyString(), anyString(), eq("mysql_transaction_commit"), anyLong(),
            eq("ok"), isNull());
        verify(structuredLogger, never()).stageDone(anyString(), anyString(), eq("mysql_transaction_rollback"),
            anyLong(), anyString(), isNull());
    }

    @Test
    @DisplayName("REQUIRES_NEW와 바깥 트랜잭션을 각각 기록한다")
    void recordsRequiresNewAndOuterTransactionsSeparately() throws Exception {
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        JdbcTimingContext.begin(structuredLogger, "trace-123", "/api/v1/test");
        JdbcTransactionTimingListener listener = new JdbcTransactionTimingListener();
        TransactionExecution outer = newTransaction();
        TransactionExecution inner = newTransaction();
        listener.beforeBegin(outer);
        listener.beforeBegin(inner);
        Connection innerConnection = timingDataSource(mock(Connection.class)).getConnection();
        Connection outerConnection = timingDataSource(mock(Connection.class)).getConnection();

        innerConnection.commit();
        listener.afterCommit(inner, null);
        outerConnection.commit();
        listener.afterCommit(outer, null);

        verify(structuredLogger, org.mockito.Mockito.times(2)).stageDone(anyString(), anyString(),
            eq("mysql_transaction_commit"), anyLong(), eq("ok"), isNull());
    }

    @Test
    @DisplayName("한 HTTP 요청 안의 순차 트랜잭션을 각각 기록한다")
    void recordsSequentialTransactionsSeparately() throws Exception {
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        JdbcTimingContext.begin(structuredLogger, "trace-123", "/api/v1/test");
        JdbcTransactionTimingListener listener = new JdbcTransactionTimingListener();
        TransactionExecution first = newTransaction();
        TransactionExecution second = newTransaction();
        listener.beforeBegin(first);
        timingDataSource(mock(Connection.class)).getConnection().commit();
        listener.afterCommit(first, null);
        listener.beforeBegin(second);
        timingDataSource(mock(Connection.class)).getConnection().commit();
        listener.afterCommit(second, null);

        verify(structuredLogger, org.mockito.Mockito.times(2)).stageDone(anyString(), anyString(),
            eq("mysql_transaction_commit"), anyLong(), eq("ok"), isNull());
    }

    @Test
    @DisplayName("정상 rollback을 commit과 구분해 기록한다")
    void recordsRollbackAtJdbcBoundary() throws Exception {
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        JdbcTimingContext.begin(structuredLogger, "trace-123", "/api/v1/test");
        JdbcTransactionTimingListener listener = new JdbcTransactionTimingListener();
        TransactionExecution transaction = newTransaction();
        listener.beforeBegin(transaction);
        Connection connection = timingDataSource(mock(Connection.class)).getConnection();

        connection.rollback();
        listener.afterRollback(transaction, null);

        verify(structuredLogger).stageDone(anyString(), anyString(), eq("mysql_transaction_rollback"), anyLong(),
            eq("ok"), isNull());
    }

    @Test
    @DisplayName("rollback 실패를 commit 성공이나 rollback 성공으로 바꾸지 않는다")
    void recordsRollbackFailureWithoutClaimingSuccess() throws Exception {
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        JdbcTimingContext.begin(structuredLogger, "trace-123", "/api/v1/test");
        JdbcTransactionTimingListener listener = new JdbcTransactionTimingListener();
        TransactionExecution transaction = newTransaction();
        SQLException rollbackFailure = new SQLException("rollback failed");
        listener.beforeBegin(transaction);
        Connection rawConnection = mock(Connection.class);
        org.mockito.BDDMockito.willThrow(rollbackFailure).given(rawConnection).rollback();
        Connection connection = timingDataSource(rawConnection).getConnection();

        assertThatThrownBy(connection::rollback).isSameAs(rollbackFailure);
        listener.afterRollback(transaction, rollbackFailure);

        verify(structuredLogger).stageDone(anyString(), anyString(), eq("mysql_transaction_rollback"), anyLong(),
            eq("fail"), isNull());
        verify(structuredLogger, never()).stageDone(anyString(), anyString(), eq("mysql_transaction_commit"),
            anyLong(), anyString(), isNull());
    }

    @Test
    @DisplayName("JDBC 경계가 없는 transaction은 생략하고 상태를 정리한다")
    void omitsTransactionWithoutJdbcBoundaryAndClearsItsState() throws Exception {
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        JdbcTimingContext.begin(structuredLogger, "trace-123", "/api/v1/test");
        JdbcTransactionTimingListener listener = new JdbcTransactionTimingListener();
        TransactionExecution withoutBoundary = newTransaction();
        listener.beforeBegin(withoutBoundary);
        listener.afterCommit(withoutBoundary, null);
        TransactionExecution transaction = newTransaction();
        listener.beforeBegin(transaction);
        Connection connection = timingDataSource(mock(Connection.class)).getConnection();

        connection.commit();
        listener.afterCommit(transaction, null);
        connection.commit();

        verify(structuredLogger).stageDone(anyString(), anyString(), eq("mysql_transaction_commit"), anyLong(),
            eq("ok"), isNull());
    }

    @Test
    @DisplayName("Spring Boot customizer가 listener를 기존 transaction manager에 연결한다")
    void registersListenerThroughBootTransactionManagerCustomization() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TransactionManagerCustomizationAutoConfiguration.class))
            .withBean(org.springframework.transaction.TransactionExecutionListener.class,
                JdbcTransactionTimingListener::new)
            .run(context -> {
                TransactionManagerCustomizers customizers = context.getBean(TransactionManagerCustomizers.class);
                DataSourceTransactionManager transactionManager =
                    new DataSourceTransactionManager(mock(DataSource.class));

                customizers.customize(transactionManager);

                assertThat(transactionManager.getTransactionExecutionListeners())
                    .anyMatch(JdbcTransactionTimingListener.class::isInstance);
            });
    }

    @Test
    @DisplayName("begin 실패를 구분하고 다음 트랜잭션 상태를 오염시키지 않는다")
    void recordsBeginFailureAndClearsItsState() throws Exception {
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        JdbcTimingContext.begin(structuredLogger, "trace-123", "/api/v1/test");
        JdbcTransactionTimingListener listener = new JdbcTransactionTimingListener();
        TransactionExecution failed = newTransaction();
        SQLException beginFailure = new SQLException("begin failed");
        listener.beforeBegin(failed);
        listener.afterBegin(failed, beginFailure);
        TransactionExecution next = newTransaction();
        listener.beforeBegin(next);
        Connection connection = timingDataSource(mock(Connection.class)).getConnection();
        connection.commit();
        listener.afterCommit(next, null);

        verify(structuredLogger).stageDone(anyString(), anyString(), eq("mysql_transaction_begin"), anyLong(),
            eq("fail"), isNull());
        verify(structuredLogger).stageDone(anyString(), anyString(), eq("mysql_transaction_commit"), anyLong(),
            eq("ok"), isNull());
    }

    @Test
    @DisplayName("commit 실패와 계측 실패가 원본 예외를 바꾸거나 rollback으로 오기록하지 않는다")
    void preservesCommitFailureWhenLoggingFails() throws Exception {
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        doThrow(new IllegalStateException("log failed")).when(structuredLogger).stageDone(anyString(), anyString(),
            eq("mysql_transaction_commit"), anyLong(), anyString(), isNull());
        JdbcTimingContext.begin(structuredLogger, "trace-123", "/api/v1/test");
        JdbcTransactionTimingListener listener = new JdbcTransactionTimingListener();
        TransactionExecution transaction = newTransaction();
        SQLException commitFailure = new SQLException("commit failed");
        listener.beforeBegin(transaction);
        Connection rawConnection = mock(Connection.class);
        org.mockito.BDDMockito.willThrow(commitFailure).given(rawConnection).commit();
        Connection connection = timingDataSource(rawConnection).getConnection();

        assertThatThrownBy(connection::commit).isSameAs(commitFailure);
        listener.afterCommit(transaction, commitFailure);

        verify(structuredLogger).stageDone(anyString(), anyString(), eq("mysql_transaction_commit"), anyLong(),
            eq("fail"), isNull());
        verify(structuredLogger, never()).stageDone(anyString(), anyString(), eq("mysql_transaction_rollback"),
            anyLong(), anyString(), isNull());
    }

    private TransactionExecution newTransaction() {
        TransactionExecution transaction = mock(TransactionExecution.class);
        given(transaction.isNewTransaction()).willReturn(true);
        return transaction;
    }

    private JdbcTimingDataSource timingDataSource(Connection connection) throws SQLException {
        DataSource rawDataSource = mock(DataSource.class);
        given(rawDataSource.getConnection()).willReturn(connection);
        return new JdbcTimingDataSource(rawDataSource);
    }
}
