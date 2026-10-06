package com.homes.zipsai.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

import com.homes.zipsai.conversation.ConversationTestFixture;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Import(ConversationTestFixture.class)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:mysql:8.4.11:///zipsai_timing?TC_DAEMON=true",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.hibernate.naming.physical-strategy=org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl",
    "spring.jpa.defer-datasource-initialization=false",
    "spring.flyway.enabled=true"
})
class JdbcTimingRequestIntegrationTest {
    @Autowired
    RequestIdFilter requestIdFilter;

    @Autowired
    ConversationTestFixture conversationTestFixture;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    StructuredLogger structuredLogger;


    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private String previousActiveProfiles;

    @BeforeEach
    void setUp() {
        previousActiveProfiles = System.getProperty("spring.profiles.active");
        System.setProperty("spring.profiles.active", "local");
        logger = (Logger) LoggerFactory.getLogger("structured-events");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        if (previousActiveProfiles == null) {
            System.clearProperty("spring.profiles.active");
        } else {
            System.setProperty("spring.profiles.active", previousActiveProfiles);
        }
    }

    @Test
    @DisplayName("JPA flush와 commit SQL을 요청 trace와 URI로 한 번 기록하고 request_done과 합계를 맞춘다")
    void logsJpaFlushAndCommitForTheHttpRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/residents/me/conversations");
        request.setQueryString("preview=true");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Instant before = Instant.now().minusSeconds(1);

        requestIdFilter.doFilter(request, response, (req, res) -> conversationTestFixture.unconnectedResident());

        Instant after = Instant.now().plusSeconds(1);
        String traceId = response.getHeader("X-Request-Id");
        List<JsonNode> events = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            events.add(objectMapper.readTree(event.getFormattedMessage()));
        }
        List<JsonNode> mysqlStages = events.stream()
            .filter(event -> "stage_done".equals(event.path("event").asText()))
            .filter(event -> "mysql".equals(event.path("stage").asText()))
            .toList();
        List<JsonNode> requestDoneEvents = events.stream()
            .filter(event -> "request_done".equals(event.path("event").asText()))
            .toList();

        assertThat(mysqlStages).hasSize(1);
        JsonNode stage = mysqlStages.getFirst();
        assertThat(stage.path("timestamp").asText()).isNotBlank();
        assertThat(Instant.parse(stage.path("timestamp").asText())).isBetween(before, after);
        assertThat(stage.path("level").asText()).isEqualTo("INFO");
        assertThat(stage.path("service").asText()).isEqualTo("backend");
        assertThat(stage.path("trace_id").asText()).isEqualTo(traceId);
        assertThat(stage.path("route").asText()).isEqualTo("/api/v1/residents/me/conversations");
        assertThat(stage.path("duration_ms").isNumber()).isTrue();
        assertThat(stage.path("duration_ms").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(stage.path("stage").asText()).isEqualTo("mysql");
        assertThat(stage.path("outcome").asText()).isEqualTo("ok");
        assertThat(stage.path("error_code").isNull()).isTrue();

        assertThat(requestDoneEvents).hasSize(1);
        JsonNode requestDone = requestDoneEvents.getFirst();
        assertThat(requestDone.path("trace_id").asText()).isEqualTo(traceId);
        assertThat(requestDone.path("db_ms").asLong()).isEqualTo(stage.path("duration_ms").asLong());
    }

    @Test
    @DisplayName("MySQL JPA flush의 실제 JDBC 경과 시간을 나노초 합계로 수집한다")
    void collectsActualJpaFlushAtNanosecondResolution() {
        JdbcTimingContext.begin(structuredLogger, "trace-123", "/api/v1/residents/me/conversations");
        JdbcTimingContext.Measurement measurement;
        try {
            conversationTestFixture.unconnectedResident();
        } finally {
            measurement = JdbcTimingContext.finish();
        }

        assertThat(measurement.durationNanos()).isPositive();
        assertThat(measurement.outcome()).isEqualTo("ok");
    }
}
