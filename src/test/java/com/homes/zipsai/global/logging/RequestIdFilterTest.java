package com.homes.zipsai.global.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {
    @Test
    @DisplayName("DB 접근이 없는 요청도 mysql 단계를 0으로 기록한다")
    void logsZeroDurationForRequestWithoutDatabaseAccess() throws Exception {
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        RequestIdFilter filter = new RequestIdFilter(structuredLogger);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/status");
        request.setQueryString("verbose=true");
        MockHttpServletResponse response = new MockHttpServletResponse();
        doAnswer(invocation -> {
            assertThat(MDC.get("dbMs")).isEqualTo("0");
            return null;
        }).when(structuredLogger).requestDone(anyString(), anyString(), anyString(), anyInt(), anyLong(), isNull());

        filter.doFilter(request, response, (req, res) -> { });

        String traceId = response.getHeader("X-Request-Id");
        verify(structuredLogger).stageDone(eq(traceId), eq("/api/v1/status"), eq("mysql"), eq(0L), eq("ok"),
            isNull());
        verify(structuredLogger).requestDone(eq(traceId), eq("/api/v1/status"), eq("GET"), anyInt(), anyLong(),
            isNull());
        verify(structuredLogger, never()).stageDone(anyString(), anyString(), eq("mysql_connection_acquire"),
            anyLong(), anyString(), isNull());
        assertThat(MDC.get("dbMs")).isNull();
    }

    @Test
    @DisplayName("두 연결 획득 호출을 각각 요청 상관정보와 함께 기록한다")
    void logsEachConnectionAcquisitionWithRequestContext() throws Exception {
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        List<String> methods = new ArrayList<>();
        doAnswer(invocation -> {
            methods.add(MDC.get("method"));
            return null;
        }).when(structuredLogger).stageDone(anyString(), anyString(), anyString(), anyLong(), anyString(), isNull());
        DataSource rawDataSource = mock(DataSource.class);
        given(rawDataSource.getConnection()).willReturn(mock(Connection.class));
        given(rawDataSource.getConnection("db-user", "secret")).willReturn(mock(Connection.class));
        JdbcTimingDataSource dataSource = new JdbcTimingDataSource(rawDataSource);
        RequestIdFilter filter = new RequestIdFilter(structuredLogger);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            try {
                try (Connection connection = dataSource.getConnection()) {
                    assertThat(connection).isNotNull();
                }
                try (Connection connection = dataSource.getConnection("db-user", "secret")) {
                    assertThat(connection).isNotNull();
                }
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
        });

        String traceId = response.getHeader("X-Request-Id");
        ArgumentCaptor<String> traceIds = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> routes = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> stages = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> durations = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> outcomes = ArgumentCaptor.forClass(String.class);
        verify(structuredLogger, times(3)).stageDone(traceIds.capture(), routes.capture(), stages.capture(),
            durations.capture(), outcomes.capture(), isNull());

        assertThat(traceIds.getAllValues()).containsExactly(traceId, traceId, traceId);
        assertThat(routes.getAllValues()).containsOnly("/api/v1/test");
        assertThat(stages.getAllValues()).containsExactly("mysql_connection_acquire", "mysql_connection_acquire",
            "mysql");
        assertThat(durations.getAllValues()).allMatch(duration -> duration >= 0);
        assertThat(outcomes.getAllValues()).containsExactly("ok", "ok", "ok");
        assertThat(methods).containsExactly("POST", "POST", "POST");
    }

    @Test
    @DisplayName("연결 timeout을 획득 실패로 기록하고 원본 예외를 유지한다")
    void logsConnectionAcquisitionTimeoutWithoutReplacingOriginalException() throws Exception {
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        DataSource rawDataSource = mock(DataSource.class);
        SQLTimeoutException timeout = new SQLTimeoutException("connection timeout", "HYT00");
        given(rawDataSource.getConnection("db-user", "secret")).willThrow(timeout);
        JdbcTimingDataSource dataSource = new JdbcTimingDataSource(rawDataSource);
        RequestIdFilter filter = new RequestIdFilter(structuredLogger);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
            assertThatThrownBy(() -> dataSource.getConnection("db-user", "secret")).isSameAs(timeout));

        String traceId = response.getHeader("X-Request-Id");
        verify(structuredLogger).stageDone(eq(traceId), eq("/api/v1/test"), eq("mysql_connection_acquire"),
            anyLong(), eq("fail"), isNull());
        verify(structuredLogger).stageDone(eq(traceId), eq("/api/v1/test"), eq("mysql"), eq(0L), eq("fail"),
            isNull());
    }

    @Test
    @DisplayName("SQL 실패 시간은 DB 단계 실패로 남기고 기존 예외를 유지한다")
    void logsDatabaseFailureWithoutReplacingOriginalException() throws Exception {
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        DataSource rawDataSource = mock(DataSource.class);
        Connection rawConnection = mock(Connection.class);
        Statement rawStatement = mock(Statement.class);
        SQLException databaseFailure = new SQLException("original database failure", "42000", 7);
        given(rawDataSource.getConnection()).willReturn(rawConnection);
        given(rawConnection.createStatement()).willReturn(rawStatement);
        given(rawStatement.execute("broken SQL")).willThrow(databaseFailure);
        JdbcTimingDataSource dataSource = new JdbcTimingDataSource(rawDataSource);
        RequestIdFilter filter = new RequestIdFilter(structuredLogger);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/messages");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.execute("broken SQL")).isSameAs(databaseFailure);
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
        });

        String traceId = response.getHeader("X-Request-Id");
        verify(structuredLogger).stageDone(eq(traceId), eq("/api/v1/messages"), eq("mysql"), anyLong(), eq("fail"),
            isNull());
        verify(structuredLogger).requestDone(eq(traceId), eq("/api/v1/messages"), eq("POST"), eq(200), anyLong(),
            isNull());
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("dbMs")).isNull();
    }

    @Test
    @DisplayName("DB 성공 후 요청이 실패해도 mysql 단계는 성공으로 남긴다")
    void keepsDatabaseOutcomeSuccessfulWhenRequestFailsLater() throws Exception {
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        DataSource rawDataSource = mock(DataSource.class);
        Connection rawConnection = mock(Connection.class);
        Statement rawStatement = mock(Statement.class);
        given(rawDataSource.getConnection()).willReturn(rawConnection);
        given(rawConnection.createStatement()).willReturn(rawStatement);
        given(rawStatement.execute("SELECT 1")).willReturn(false);
        JdbcTimingDataSource dataSource = new JdbcTimingDataSource(rawDataSource);
        RequestIdFilter filter = new RequestIdFilter(structuredLogger);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/messages");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletException requestFailure = new ServletException("later request failure");

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                assertThat(statement.execute("SELECT 1")).isFalse();
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
            response.setStatus(500);
            throw requestFailure;
        })).isSameAs(requestFailure);

        String traceId = response.getHeader("X-Request-Id");
        verify(structuredLogger).stageDone(eq(traceId), eq("/api/v1/messages"), eq("mysql"), anyLong(), eq("ok"),
            isNull());
        verify(structuredLogger).requestDone(eq(traceId), eq("/api/v1/messages"), eq("POST"), eq(500), anyLong(),
            isNull());
    }

    @Test
    @DisplayName("연속 요청에서 DB 실패 상태를 다음 DB 없는 요청에 넘기지 않는다")
    void startsEachRequestWithAnIsolatedDatabaseMeasurement() throws Exception {
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        DataSource rawDataSource = mock(DataSource.class);
        SQLException failure = new SQLException("first request connection failure", "08001", 22);
        given(rawDataSource.getConnection()).willThrow(failure);
        JdbcTimingDataSource dataSource = new JdbcTimingDataSource(rawDataSource);
        RequestIdFilter filter = new RequestIdFilter(structuredLogger);
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/first"), firstResponse, (req, res) ->
            assertThatThrownBy(dataSource::getConnection).isSameAs(failure));
        filter.doFilter(new MockHttpServletRequest("GET", "/second"), secondResponse, (req, res) -> { });

        ArgumentCaptor<String> traceIds = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> durations = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> outcomes = ArgumentCaptor.forClass(String.class);
        verify(structuredLogger, times(2)).stageDone(traceIds.capture(), anyString(), eq("mysql"), durations.capture(),
            outcomes.capture(), isNull());
        assertThat(traceIds.getAllValues()).hasSize(2).doesNotHaveDuplicates();
        assertThat(durations.getAllValues()).containsExactly(0L, 0L);
        assertThat(outcomes.getAllValues()).containsExactly("fail", "ok");
    }

    @Test
    @DisplayName("로깅 실패 후에도 원래 요청 예외와 문맥 정리를 보존한다")
    void preservesRequestFailureAndClearsContextWhenLoggingFails() throws Exception {
        StructuredLogger structuredLogger = mock(StructuredLogger.class);
        doThrow(new IllegalStateException("start log failed")).when(structuredLogger)
            .requestStarted(anyString(), anyString(), anyString());
        doThrow(new IllegalStateException("stage log failed")).when(structuredLogger)
            .stageDone(anyString(), anyString(), anyString(), anyLong(), anyString(), isNull());
        doThrow(new IllegalStateException("request log failed")).when(structuredLogger)
            .requestDone(anyString(), anyString(), anyString(), anyInt(), anyLong(), isNull());
        RequestIdFilter filter = new RequestIdFilter(structuredLogger);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/failure");
        MockHttpServletResponse response = new MockHttpServletResponse();
        IllegalStateException requestFailure = new IllegalStateException("original request failure");

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw requestFailure;
        })).isSameAs(requestFailure);

        verify(structuredLogger).stageDone(anyString(), eq("/api/v1/failure"), eq("mysql"), eq(0L), eq("ok"),
            isNull());
        verify(structuredLogger).requestDone(anyString(), eq("/api/v1/failure"), eq("GET"), anyInt(), anyLong(),
            isNull());
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("route")).isNull();
        assertThat(MDC.get("dbMs")).isNull();
    }

    @Test
    @DisplayName("응답 헤더는 요청 문맥과 같고 문맥을 정리한다")
    void responseHeaderMatchesRequestContextAndContextIsCleared() throws Exception {
        RequestIdFilter filter = new RequestIdFilter(mock(StructuredLogger.class));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/conversations");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            String traceId = MDC.get("traceId");
            assertThat(UUID.fromString(traceId).toString()).isEqualTo(traceId);
            assertThat(response.getHeader("X-Request-Id")).isEqualTo(traceId);
            assertThat(MDC.get("route")).isEqualTo("/api/v1/conversations");
            assertThat(MDC.get("method")).isEqualTo("POST");
            response.setStatus(401);
        });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("route")).isNull();
        assertThat(MDC.get("method")).isNull();
    }
}
