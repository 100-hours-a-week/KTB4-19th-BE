package com.homes.zipsai.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientObservationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestClient;

import com.homes.zipsai.building.client.KakaoAddressClient;
import com.homes.zipsai.building.client.KakaoAddressResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class KakaoHttpObservationIntegrationTest {
    private static final String ROUTE = "/api/v1/managers/me/building";
    private static final String ADDRESS = "서울 강남구 테헤란로 152";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger("structured-events");
    private final ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
    private HttpServer httpServer;
    private ExecutorService executorService;
    private ApplicationContextRunner applicationContextRunner;
    private volatile int responseStatus;
    private volatile String responseBody;
    private volatile boolean blockResponse;
    private CountDownLatch releaseResponse;

    @BeforeEach
    void setUp() throws IOException {
        responseStatus = 200;
        responseBody = """
            {"meta":{"total_count":1},"documents":[
             {"address_type":"ROAD_ADDR","x":"127.0365","y":"37.5000"}]}
            """;
        releaseResponse = new CountDownLatch(1);
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executorService = Executors.newVirtualThreadPerTaskExecutor();
        httpServer.setExecutor(executorService);
        httpServer.createContext("/v2/local/search/address.json", this::respond);
        httpServer.start();
        listAppender.start();
        logger.addAppender(listAppender);
        applicationContextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration.class,
                RestClientAutoConfiguration.class, RestClientObservationAutoConfiguration.class))
            .withUserConfiguration(ExternalApiHttpObservationHandler.class, KakaoAddressClient.class)
            .withBean(StructuredLogger.class, () -> new StructuredLogger(objectMapper))
            .withPropertyValues("app.ai.client=stub", "app.ai.base-url=http://ai.test:8000",
                "app.kakao.base-url=http://127.0.0.1:" + httpServer.getAddress().getPort(),
                "app.kakao.api-key=test-kakao-key",
                "app.external-api.targets.ai.base-url=${app.ai.base-url}",
                "app.external-api.targets.ai.stage=ai_api",
                "app.external-api.targets.ai.error-code=AI_UNAVAILABLE",
                "app.external-api.targets.kakao.base-url=${app.kakao.base-url}",
                "app.external-api.targets.kakao.stage=kakao_api",
                "app.external-api.targets.kakao.error-code=KAKAO_UNAVAILABLE");
    }

    @AfterEach
    void tearDown() {
        releaseResponse.countDown();
        httpServer.stop(0);
        executorService.shutdownNow();
        logger.detachAppender(listAppender);
        listAppender.stop();
    }

    @Test
    @DisplayName("주입된 RestClient와 자동 Observation으로 Kakao 시간을 기존 JSON 형식에 기록한다")
    void logsKakaoSuccessThroughBootObservation() {
        query(true);

        assertLogs("ok", null);
        assertThat(listAppender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
            .doesNotContain("test-kakao-key", ADDRESS, "query="));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 429, 503})
    @DisplayName("Kakao HTTP 오류는 호출 실패로 기록해도 좌표 없이 요청을 성공시킨다")
    void logsHttpFailureWithoutFailingRequest(int status) {
        responseStatus = status;
        responseBody = "{\"message\":\"test error\"}";

        query(false);

        assertLogs("fail", "KAKAO_UNAVAILABLE");
    }

    @Test
    @DisplayName("실제 응답 대기 시간 초과도 실패 시간으로 기록하고 좌표 조회 예외를 전파하지 않는다")
    void logsReadTimeoutWithoutFailingRequest() {
        blockResponse = true;
        applicationContextRunner = applicationContextRunner.withPropertyValues("app.kakao.read-timeout=PT0.1S");

        query(false);

        assertLogs("fail", "KAKAO_UNAVAILABLE");
        assertThat(kakaoStage().path("duration_ms").asLong()).isGreaterThanOrEqualTo(100);
    }

    @Test
    @DisplayName("검색 결과가 없어도 HTTP 200 호출은 성공으로 기록한다")
    void logsEmptySearchAsHttpSuccess() {
        responseBody = "{\"meta\":{\"total_count\":0},\"documents\":[]}";

        query(false);

        assertLogs("ok", null);
    }

    @Test
    @DisplayName("빈 API 키로 보낸 HTTP 요청의 오류 응답은 공통 Handler가 실패로 기록한다")
    void logsFailureWithoutApiKey() {
        responseStatus = 401;
        responseBody = "{\"message\":\"test error\"}";
        applicationContextRunner = applicationContextRunner.withPropertyValues("app.kakao.api-key=");

        query(false);

        assertLogs("fail", "KAKAO_UNAVAILABLE");
    }

    @Test
    @DisplayName("AI HTTP 호출도 자동 Observation으로 기존 stage와 요청 요약 시간을 유지한다")
    void preservesAiLoggingThroughBootObservation() {
        String baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
        applicationContextRunner.withPropertyValues("app.ai.client=http", "app.ai.base-url=" + baseUrl,
            "app.kakao.base-url=https://dapi.kakao.com").run(context -> {
                RequestIdFilter filter = new RequestIdFilter(context.getBean(StructuredLogger.class));
                MockHttpServletRequest request = new MockHttpServletRequest("POST", ROUTE);
                MockHttpServletResponse response = new MockHttpServletResponse();
                RestClient client = context.getBean(RestClient.Builder.class).baseUrl(baseUrl).build();

                filter.doFilter(request, response, (req, res) -> {
                    assertThat(client.get().uri("/v2/local/search/address.json").retrieve().body(String.class))
                        .contains("total_count");
                    response.setStatus(201);
                });
            });

        List<JsonNode> stages = events().stream()
            .filter(event -> "ai_api".equals(event.path("stage").asText())).toList();
        assertThat(stages).hasSize(1);
        JsonNode requestDone = events().stream()
            .filter(event -> "request_done".equals(event.path("event").asText())).findFirst().orElseThrow();
        assertThat(requestDone.path("ai_api_ms").asLong()).isEqualTo(stages.getFirst().path("duration_ms").asLong());
        assertThat(requestDone.path("trace_id").asText()).isEqualTo(stages.getFirst().path("trace_id").asText());
        assertThat(stages.getFirst().path("outcome").asText()).isEqualTo("ok");
    }

    private void query(boolean expectedCoordinates) {
        applicationContextRunner.run(context -> {
            assertThat(context).hasSingleBean(ExternalApiHttpObservationHandler.class);
            RequestIdFilter filter = new RequestIdFilter(context.getBean(StructuredLogger.class));
            MockHttpServletRequest request = new MockHttpServletRequest("POST", ROUTE);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (req, res) -> {
                assertThat(context.getBean(KakaoAddressClient.class).findCoordinates(ADDRESS)
                    instanceof KakaoAddressResult.Success)
                    .isEqualTo(expectedCoordinates);
                response.setStatus(201);
            });
        });
    }

    private void assertLogs(String outcome, String errorCode) {
        JsonNode stage = kakaoStage();
        JsonNode requestDone = events().stream()
            .filter(event -> "request_done".equals(event.path("event").asText())).findFirst().orElseThrow();
        assertThat(stage.path("event").asText()).isEqualTo("stage_done");
        assertThat(stage.path("timestamp").asText()).isNotBlank();
        assertThat(stage.path("level").asText()).isEqualTo("INFO");
        assertThat(stage.path("service").asText()).isEqualTo("backend");
        assertThat(stage.path("trace_id").asText()).isEqualTo(requestDone.path("trace_id").asText()).isNotBlank();
        assertThat(stage.path("route").asText()).isEqualTo(ROUTE);
        assertThat(stage.path("method").asText()).isEqualTo("POST");
        assertThat(stage.path("duration_ms").isNumber()).isTrue();
        assertThat(stage.path("duration_ms").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(stage.path("outcome").asText()).isEqualTo(outcome);
        if (errorCode == null) {
            assertThat(stage.path("error_code").isNull()).isTrue();
        } else {
            assertThat(stage.path("error_code").asText()).isEqualTo(errorCode);
        }
        assertThat(requestDone.path("status_code").asInt()).isEqualTo(201);
        assertThat(requestDone.path("outcome").asText()).isEqualTo("ok");
        assertThat(requestDone.path("ai_api_ms").isNull()).isTrue();
    }

    private JsonNode kakaoStage() {
        List<JsonNode> stages = events().stream()
            .filter(event -> "kakao_api".equals(event.path("stage").asText())).toList();
        assertThat(stages).hasSize(1);
        return stages.getFirst();
    }

    private List<JsonNode> events() {
        return listAppender.list.stream().map(event -> objectMapper.readTree(event.getFormattedMessage())).toList();
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (blockResponse) {
                try {
                    releaseResponse.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
