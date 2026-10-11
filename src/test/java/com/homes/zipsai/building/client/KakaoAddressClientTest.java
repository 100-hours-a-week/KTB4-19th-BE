package com.homes.zipsai.building.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class KakaoAddressClientTest {

    private static final String ADDRESS = "서울 강남구 테헤란로 123 & 테스트";
    private static final String DOCUMENT = """
        {"address_type":"ROAD_ADDR","address_name":"서울 강남구 테헤란로 123",
         "x":"127.0276","y":"37.4979","road_address":{"building_name":"테스트빌"}}
        """;

    private HttpServer server;
    private ExecutorService executor;
    private KakaoAddressClient client;
    private String baseUrl;
    private volatile int responseStatus;
    private volatile String responseBody;
    private volatile URI requestUri;
    private volatile String requestMethod;
    private volatile String authorization;
    private volatile boolean blockResponse;
    private CountDownLatch requestReceived;
    private CountDownLatch releaseResponse;

    @BeforeEach
    void setUp() throws IOException {
        responseStatus = 200;
        responseBody = response(1, DOCUMENT);
        requestReceived = new CountDownLatch(1);
        releaseResponse = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/v2/local/search/address.json", this::respond);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = client("test-kakao-key", Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        releaseResponse.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROAD_ADDR", "REGION_ADDR"})
    @DisplayName("주소를 인코딩하고 Kakao 인증 헤더로 조회하여 WGS 84 Point를 반환한다")
    void resolvesOneBuildingAddress(String addressType) {
        responseBody = response(1, DOCUMENT.replace("ROAD_ADDR", addressType));

        var coordinates = client.findCoordinates(ADDRESS);

        assertThat(coordinates).isPresent();
        assertThat(coordinates.orElseThrow().getX()).isEqualTo(127.0276);
        assertThat(coordinates.orElseThrow().getY()).isEqualTo(37.4979);
        assertThat(coordinates.orElseThrow().getSRID()).isEqualTo(4326);
        assertThat(requestMethod).isEqualTo("GET");
        assertThat(authorization).isEqualTo("KakaoAK test-kakao-key");
        assertThat(URLDecoder.decode(requestUri.getRawQuery(), StandardCharsets.UTF_8))
            .isEqualTo("query=" + ADDRESS + "&analyze_type=exact&size=2");
        assertThat(requestUri.getRawQuery()).contains("%26");
    }

    @Test
    @DisplayName("검색 결과가 없으면 좌표 없이 반환한다")
    void returnsEmptyForNoResults() {
        responseBody = response(0, "");

        assertThat(client.findCoordinates(ADDRESS)).isEmpty();
        assertRequestSent();
    }

    @Test
    @DisplayName("여러 주소가 검색되면 첫 번째 결과를 임의로 선택하지 않는다")
    void returnsEmptyForAmbiguousResults() {
        responseBody = response(2, DOCUMENT + "," + DOCUMENT);

        assertThat(client.findCoordinates(ADDRESS)).isEmpty();
        assertRequestSent();
    }

    @Test
    @DisplayName("응답 문서가 하나여도 전체 검색 결과가 여러 개면 좌표를 반환하지 않는다")
    void returnsEmptyForMoreResultsThanReturnedDocuments() {
        responseBody = response(2, DOCUMENT);

        assertThat(client.findCoordinates(ADDRESS)).isEmpty();
        assertRequestSent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REGION", "ROAD", "UNKNOWN"})
    @DisplayName("건물 주소가 아닌 지역명이나 도로명 결과는 좌표로 저장하지 않는다")
    void returnsEmptyForNonBuildingAddress(String addressType) {
        responseBody = response(1, DOCUMENT.replace("ROAD_ADDR", addressType));

        assertThat(client.findCoordinates(ADDRESS)).isEmpty();
        assertRequestSent();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 500, 503})
    @DisplayName("Kakao HTTP 오류는 좌표 없이 반환한다")
    void returnsEmptyForHttpErrors(int status) {
        responseStatus = status;
        responseBody = "{\"message\":\"test error\"}";

        assertThat(client.findCoordinates(ADDRESS)).isEmpty();
        assertRequestSent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{broken", "null", "{}", "{\"meta\":{\"total_count\":1},\"documents\":null}",
        "{\"meta\":{\"total_count\":1},\"documents\":[null]}",
        "{\"meta\":{\"total_count\":1},\"documents\":[]}",
        "{\"meta\":{\"total_count\":1},\"documents\":[{\"address_type\":\"ROAD_ADDR\"}]}"
    })
    @DisplayName("손상되거나 필수 값이 없는 응답은 좌표 없이 반환한다")
    void returnsEmptyForMalformedResponse(String body) {
        responseBody = body;

        assertThat(client.findCoordinates(ADDRESS)).isEmpty();
        assertRequestSent();
    }

    @ParameterizedTest
    @CsvSource({"bad,37", "NaN,37", "Infinity,37", "127,NaN", "181,37", "127,91", "-180,37"})
    @DisplayName("숫자가 아니거나 유효 범위를 벗어난 좌표는 저장 대상에서 제외한다")
    void returnsEmptyForInvalidCoordinates(String longitude, String latitude) {
        responseBody = response(1, DOCUMENT.replace("127.0276", longitude).replace("37.4979", latitude));

        assertThat(client.findCoordinates(ADDRESS)).isEmpty();
        assertRequestSent();
    }

    @Test
    @DisplayName("응답 대기 시간이 초과되면 예외를 전파하지 않고 좌표 없이 반환한다")
    void returnsEmptyOnReadTimeout() throws InterruptedException {
        blockResponse = true;
        client = client("test-kakao-key", Duration.ofMillis(100));

        assertThat(client.findCoordinates(ADDRESS)).isEmpty();
        assertThat(requestReceived.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("API 키가 없어도 HTTP 요청을 보내고 오류 응답이면 좌표 없이 반환한다")
    void sendsRequestWithoutApiKeyAndReturnsEmptyOnHttpError() {
        responseStatus = 401;
        responseBody = "{\"message\":\"test error\"}";
        client = client("", Duration.ofSeconds(1));

        assertThat(client.findCoordinates(ADDRESS)).isEmpty();
        assertRequestSent();
    }

    private KakaoAddressClient client(String apiKey, Duration readTimeout) {
        return new KakaoAddressClient(RestClient.builder(), baseUrl, apiKey, Duration.ofSeconds(1), readTimeout);
    }

    private String response(int count, String documents) {
        return "{\"meta\":{\"total_count\":" + count + ",\"is_end\":true},\"documents\":[" + documents + "]}";
    }

    private void assertRequestSent() {
        assertThat(requestUri).isNotNull();
    }

    private void respond(HttpExchange exchange) throws IOException {
        requestUri = exchange.getRequestURI();
        requestMethod = exchange.getRequestMethod();
        authorization = exchange.getRequestHeaders().getFirst("Authorization");
        requestReceived.countDown();
        try {
            if (blockResponse) {
                releaseResponse.await(5, TimeUnit.SECONDS);
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }
}
