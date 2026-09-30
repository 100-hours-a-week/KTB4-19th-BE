package com.homes.zipsai.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcTimingDataSourceTest {
    @AfterEach
    void clearRequestContext() {
        JdbcTimingContext.clear();
    }

    @Test
    @DisplayName("SQL과 배치, commit, rollback 호출 시간을 요청 문맥에 기록한다")
    void recordsSqlBatchAndTransactionDurations() throws Exception {
        DataSource rawDataSource = dataSource();
        try (Connection connection = rawDataSource.getConnection();
                Statement statement = connection.createStatement()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            statement.execute("CREATE TABLE timing_test (id INT PRIMARY KEY)");
        }
        JdbcTimingDataSource dataSource = new JdbcTimingDataSource(rawDataSource);
        JdbcTimingContext.begin();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            assertThat(dataSource.isWrapperFor(DataSource.class)).isTrue();
            assertThat(dataSource.unwrap(DataSource.class)).isSameAs(dataSource);
            assertThat(connection.isWrapperFor(Connection.class)).isTrue();
            assertThat(connection.unwrap(Connection.class)).isSameAs(connection);

            Statement statement = connection.createStatement();
            statement.execute("INSERT INTO timing_test VALUES (1)");
            assertThat(statement.getConnection()).isSameAs(connection);
            PreparedStatement prepared = connection.prepareStatement("INSERT INTO timing_test VALUES (?)");
            prepared.setInt(1, 2);
            prepared.executeUpdate();
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM timing_test")) {
                assertThat(result.next()).isTrue();
            }
            Statement batch = connection.createStatement();
            batch.addBatch("INSERT INTO timing_test VALUES (3)");
            batch.addBatch("INSERT INTO timing_test VALUES (4)");
            batch.executeBatch();
            batch.addBatch("INSERT INTO timing_test VALUES (5)");
            batch.executeLargeBatch();
            connection.commit();
            connection.rollback();
        }

        JdbcTimingContext.Measurement measurement = JdbcTimingContext.finish();
        assertThat(measurement.durationNanos()).isPositive();
        assertThat(measurement.durationMillis()).isEqualTo(measurement.durationNanos() / 1_000_000);
        assertThat(measurement.outcome()).isEqualTo("ok");
    }

    @Test
    @DisplayName("Connection 프록시는 원본과 구분되는 참조 동일성과 안정적인 해시 값을 사용한다")
    void usesReferenceIdentityForConnectionProxies() throws Exception {
        DataSource rawDataSource = mock(DataSource.class);
        Connection rawConnection = mock(Connection.class);
        given(rawDataSource.getConnection()).willReturn(rawConnection);
        JdbcTimingDataSource dataSource = new JdbcTimingDataSource(rawDataSource);

        try (Connection connection = dataSource.getConnection();
                Connection otherConnection = dataSource.getConnection()) {
            assertReferenceIdentity(connection, rawConnection, otherConnection);
        }
    }

    @Test
    @DisplayName("모든 Statement 프록시는 원본과 구분되는 참조 동일성과 안정적인 해시 값을 사용한다")
    void usesReferenceIdentityForStatementProxies() throws Exception {
        DataSource rawDataSource = mock(DataSource.class);
        Connection rawConnection = mock(Connection.class);
        Statement rawStatement = mock(Statement.class);
        PreparedStatement rawPreparedStatement = mock(PreparedStatement.class);
        CallableStatement rawCallableStatement = mock(CallableStatement.class);
        given(rawDataSource.getConnection()).willReturn(rawConnection);
        given(rawConnection.createStatement()).willReturn(rawStatement);
        given(rawConnection.prepareStatement("SELECT 1")).willReturn(rawPreparedStatement);
        given(rawConnection.prepareCall("CALL check_timing()")).willReturn(rawCallableStatement);
        JdbcTimingDataSource dataSource = new JdbcTimingDataSource(rawDataSource);

        try (Connection connection = dataSource.getConnection()) {
            assertReferenceIdentity(connection.createStatement(), rawStatement, connection.createStatement());
            assertReferenceIdentity(connection.prepareStatement("SELECT 1"), rawPreparedStatement,
                connection.prepareStatement("SELECT 1"));
            assertReferenceIdentity(connection.prepareCall("CALL check_timing()"), rawCallableStatement,
                connection.prepareCall("CALL check_timing()"));
        }
    }

    @Test
    @DisplayName("실패한 SQL과 트랜잭션 예외를 유지하고 DB 단계를 실패로 기록한다")
    void preservesSqlAndTransactionExceptionsAndMarksDatabaseFailure() throws Exception {
        DataSource rawDataSource = mock(DataSource.class);
        Connection rawConnection = mock(Connection.class);
        Statement rawStatement = mock(Statement.class);
        CallableStatement rawCallableStatement = mock(CallableStatement.class);
        SQLException sqlFailure = new SQLException("original SQL failure", "42000", 17);
        SQLException commitFailure = new SQLException("original commit failure", "08006", 18);
        SQLException rollbackFailure = new SQLException("original rollback failure", "08006", 19);
        given(rawDataSource.getConnection()).willReturn(rawConnection);
        given(rawConnection.createStatement()).willReturn(rawStatement);
        given(rawConnection.prepareCall("CALL check_timing()")).willReturn(rawCallableStatement);
        given(rawStatement.execute("broken SQL")).willThrow(sqlFailure);
        given(rawCallableStatement.execute()).willReturn(false);
        willThrow(commitFailure).given(rawConnection).commit();
        willThrow(rollbackFailure).given(rawConnection).rollback();
        JdbcTimingDataSource dataSource = new JdbcTimingDataSource(rawDataSource);
        JdbcTimingContext.begin();

        try (Connection connection = dataSource.getConnection()) {
            Statement statement = connection.createStatement();
            assertThatThrownBy(() -> statement.execute("broken SQL")).isSameAs(sqlFailure);
            CallableStatement callableStatement = connection.prepareCall("CALL check_timing()");
            assertThat(callableStatement.execute()).isFalse();
            assertThatThrownBy(connection::commit).isSameAs(commitFailure);
            assertThatThrownBy(connection::rollback).isSameAs(rollbackFailure);
        }

        JdbcTimingContext.Measurement measurement = JdbcTimingContext.finish();
        assertThat(measurement.durationNanos()).isPositive();
        assertThat(measurement.durationMillis()).isEqualTo(measurement.durationNanos() / 1_000_000);
        assertThat(measurement.outcome()).isEqualTo("fail");
    }

    @Test
    @DisplayName("요청 문맥이 없는 JDBC 호출은 요청 집계에 들어가지 않는다")
    void ignoresJdbcCallsWithoutRequestContext() throws Exception {
        JdbcTimingDataSource dataSource = new JdbcTimingDataSource(dataSource());

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertThat(statement.execute("SELECT 1")).isTrue();
        }

        assertThat(JdbcTimingContext.finish().durationNanos()).isZero();
    }

    @Test
    @DisplayName("연결 획득 실패는 시간을 더하지 않고 요청 DB 단계 실패로 남긴다")
    void marksConnectionAcquisitionFailureWithoutCountingPoolWait() throws Exception {
        DataSource rawDataSource = mock(DataSource.class);
        SQLException failure = new SQLException("connection failed", "08001", 20);
        given(rawDataSource.getConnection()).willThrow(failure);
        given(rawDataSource.getConnection("test-user", "test-password")).willThrow(failure);
        JdbcTimingDataSource dataSource = new JdbcTimingDataSource(rawDataSource);
        JdbcTimingContext.begin();

        assertThatThrownBy(dataSource::getConnection).isSameAs(failure);
        assertThatThrownBy(() -> dataSource.getConnection("test-user", "test-password")).isSameAs(failure);

        JdbcTimingContext.Measurement measurement = JdbcTimingContext.finish();
        assertThat(measurement.durationNanos()).isZero();
        assertThat(measurement.outcome()).isEqualTo("fail");
    }

    @Test
    @DisplayName("Statement 준비 실패는 시간을 더하지 않고 요청 DB 단계 실패로 남긴다")
    void marksStatementPreparationFailureWithoutCountingPreparationTime() throws Exception {
        DataSource rawDataSource = mock(DataSource.class);
        Connection rawConnection = mock(Connection.class);
        SQLException failure = new SQLException("prepare failed", "42000", 21);
        given(rawDataSource.getConnection()).willReturn(rawConnection);
        given(rawConnection.createStatement()).willThrow(failure);
        given(rawConnection.prepareStatement("broken prepare")).willThrow(failure);
        given(rawConnection.prepareCall("broken call")).willThrow(failure);
        JdbcTimingDataSource dataSource = new JdbcTimingDataSource(rawDataSource);
        JdbcTimingContext.begin();

        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(connection::createStatement).isSameAs(failure);
            assertThatThrownBy(() -> connection.prepareStatement("broken prepare")).isSameAs(failure);
            assertThatThrownBy(() -> connection.prepareCall("broken call")).isSameAs(failure);
        }

        JdbcTimingContext.Measurement measurement = JdbcTimingContext.finish();
        assertThat(measurement.durationNanos()).isZero();
        assertThat(measurement.outcome()).isEqualTo("fail");
    }

    @Test
    @DisplayName("문맥이 없을 때 연결 실패를 다음 요청으로 전달하지 않는다")
    void doesNotLeakContextFreeConnectionFailureIntoNextRequest() throws Exception {
        DataSource rawDataSource = mock(DataSource.class);
        given(rawDataSource.getConnection()).willThrow(new SQLException("startup connection failed"));
        JdbcTimingDataSource dataSource = new JdbcTimingDataSource(rawDataSource);

        assertThatThrownBy(dataSource::getConnection).isInstanceOf(SQLException.class);
        JdbcTimingContext.begin();

        JdbcTimingContext.Measurement measurement = JdbcTimingContext.finish();
        assertThat(measurement.durationNanos()).isZero();
        assertThat(measurement.outcome()).isEqualTo("ok");
    }

    @Test
    @DisplayName("나노초를 먼저 합산한 뒤 밀리초로 변환한다")
    void convertsAccumulatedNanosecondsToMillis() {
        JdbcTimingContext.begin();
        JdbcTimingContext.record(1_500_000, false);
        JdbcTimingContext.record(1_500_000, false);

        JdbcTimingContext.Measurement measurement = JdbcTimingContext.finish();

        assertThat(measurement.durationNanos()).isEqualTo(3_000_000);
        assertThat(measurement.durationMillis()).isEqualTo(3);
    }

    @Test
    @DisplayName("서로 다른 스레드의 DB 시간과 실패 상태를 격리한다")
    void isolatesMeasurementsAcrossThreads() throws InterruptedException {
        JdbcTimingContext.begin();
        JdbcTimingContext.record(11, false);
        AtomicReference<JdbcTimingContext.Measurement> otherThread = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            JdbcTimingContext.begin();
            JdbcTimingContext.record(37, true);
            otherThread.set(JdbcTimingContext.finish());
        });

        thread.start();
        thread.join();

        JdbcTimingContext.Measurement currentThread = JdbcTimingContext.finish();
        assertThat(currentThread.durationNanos()).isEqualTo(11);
        assertThat(currentThread.outcome()).isEqualTo("ok");
        assertThat(otherThread.get().durationNanos()).isEqualTo(37);
        assertThat(otherThread.get().outcome()).isEqualTo("fail");
    }

    private static void assertReferenceIdentity(Object proxy, Object original, Object otherProxy) {
        assertThat(proxy.equals(proxy)).isTrue();
        assertThat(proxy.equals(null)).isFalse();
        assertThat(proxy.equals(otherProxy)).isFalse();
        assertThat(otherProxy.equals(proxy)).isFalse();
        assertThat(proxy.equals(original)).isFalse();
        assertThat(original.equals(proxy)).isFalse();
        int hashCode = proxy.hashCode();
        assertThat(hashCode).isEqualTo(System.identityHashCode(proxy));
        assertThat(proxy.hashCode()).isEqualTo(hashCode);
    }

    private static DataSource dataSource() {
        return new DriverManagerDataSource(
            "jdbc:tc:mysql:8.4.11:///jdbc_timing?TC_DAEMON=true", "test", "test");
    }

}
