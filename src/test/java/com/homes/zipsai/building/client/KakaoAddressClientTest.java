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

    private HttpServer httpServer;
    private ExecutorService executorService;
    private KakaoAddressClient kakaoAddressClient;
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
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executorService = Executors.newVirtualThreadPerTaskExecutor();
        httpServer.setExecutor(executorService);
        httpServer.createContext("/v2/local/search/address.json", this::respond);
        httpServer.start();
        baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();
        kakaoAddressClient = client("test-kakao-key", Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        releaseResponse.countDown();
        httpServer.stop(0);
        executorService.shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROAD_ADDR", "REGION_ADDR"})
    @DisplayName("주소를 인코딩하고 Kakao 인증 헤더로 조회하여 WGS 84 Point를 반환한다")
    void resolvesOneBuildingAddress(String addressType) {
        responseBody = response(1, DOCUMENT.replace("ROAD_ADDR", addressType));

        KakaoAddressResult result = kakaoAddressClient.findCoordinates(ADDRESS);

        assertThat(result).isInstanceOfSatisfying(KakaoAddressResult.Success.class, success -> {
            assertThat(success.coordinates().getX()).isEqualTo(127.0276);

            assertThat(success.coordinates().getY()).isEqualTo(37.4979);

            assertThat(success.coordinates().getSRID()).isEqualTo(4326);
        });

        assertThat(requestMethod).isEqualTo("GET");

        assertThat(authorization).isEqualTo("KakaoAK test-kakao-key");

        assertThat(URLDecoder.decode(requestUri.getRawQuery(), StandardCharsets.UTF_8))
            .isEqualTo("query=" + ADDRESS + "&analyze_type=exact&size=2");

        assertThat(requestUri.getRawQuery()).contains("%26");
    }

    @Test
    @DisplayName("검색 결과가 없으면 주소 없음으로 분류한다")
    void classifiesNoResults() {
        responseBody = response(0, "");

        assertThat(kakaoAddressClient.findCoordinates(ADDRESS)).isEqualTo(
            new KakaoAddressResult.Failure(KakaoAddressErrorType.ADDRESS_NOT_FOUND));

        assertRequestSent();
    }

    @Test
    @DisplayName("여러 주소가 검색되면 첫 번째 결과를 임의로 선택하지 않는다")
    void classifiesAmbiguousResults() {
        responseBody = response(2, DOCUMENT + "," + DOCUMENT);

        assertThat(kakaoAddressClient.findCoordinates(ADDRESS)).isEqualTo(
            new KakaoAddressResult.Failure(KakaoAddressErrorType.AMBIGUOUS_ADDRESS));

        assertRequestSent();
    }

    @Test
    @DisplayName("응답 문서가 하나여도 전체 검색 결과가 여러 개면 좌표를 반환하지 않는다")
    void classifiesMoreResultsThanReturnedDocuments() {
        responseBody = response(2, DOCUMENT);

        assertThat(kakaoAddressClient.findCoordinates(ADDRESS)).isEqualTo(
            new KakaoAddressResult.Failure(KakaoAddressErrorType.AMBIGUOUS_ADDRESS));

        assertRequestSent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REGION", "ROAD", "UNKNOWN"})
    @DisplayName("건물 주소가 아닌 지역명이나 도로명 결과는 좌표로 저장하지 않는다")
    void classifiesNonBuildingAddress(String addressType) {
        responseBody = response(1, DOCUMENT.replace("ROAD_ADDR", addressType));

        assertThat(kakaoAddressClient.findCoordinates(ADDRESS)).isEqualTo(
            new KakaoAddressResult.Failure(KakaoAddressErrorType.NON_BUILDING_ADDRESS));

        assertRequestSent();
    }

    @ParameterizedTest
    @CsvSource({"401,AUTHENTICATION_ERROR", "403,PERMISSION_ERROR", "429,QUOTA_EXCEEDED",
        "500,KAKAO_SERVER_ERROR", "503,KAKAO_SERVER_ERROR"})
    @DisplayName("Kakao HTTP 오류는 원인별 오류 유형으로 반환한다")
    void classifiesHttpErrors(int status, KakaoAddressErrorType errorType) {
        responseStatus = status;
        responseBody = "{\"message\":\"test error\"}";

        assertThat(kakaoAddressClient.findCoordinates(ADDRESS)).isEqualTo(new KakaoAddressResult.Failure(errorType));

        assertRequestSent();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{broken", "null", "{}", "{\"meta\":{\"total_count\":1},\"documents\":null}",
        "{\"meta\":{\"total_count\":1},\"documents\":[null]}",
        "{\"meta\":{\"total_count\":1},\"documents\":[]}",
        "{\"meta\":{\"total_count\":1},\"documents\":[{\"address_type\":\"ROAD_ADDR\"}]}"
    })
    @DisplayName("손상되거나 필수 값이 없는 응답은 형식 이상으로 분류한다")
    void classifiesMalformedResponse(String body) {
        responseBody = body;

        assertThat(kakaoAddressClient.findCoordinates(ADDRESS)).isEqualTo(
            new KakaoAddressResult.Failure(KakaoAddressErrorType.INVALID_RESPONSE));

        assertRequestSent();
    }

    @ParameterizedTest
    @CsvSource({"bad,37", "NaN,37", "Infinity,37", "127,NaN", "181,37", "127,91", "-180,37"})
    @DisplayName("숫자가 아니거나 유효 범위를 벗어난 좌표는 저장 대상에서 제외한다")
    void classifiesInvalidCoordinates(String longitude, String latitude) {
        responseBody = response(1, DOCUMENT.replace("127.0276", longitude).replace("37.4979", latitude));

        assertThat(kakaoAddressClient.findCoordinates(ADDRESS)).isEqualTo(
            new KakaoAddressResult.Failure(KakaoAddressErrorType.INVALID_RESPONSE));

        assertRequestSent();
    }

    @Test
    @DisplayName("응답 대기 시간이 초과되면 예외를 전파하지 않고 네트워크 오류로 반환한다")
    void classifiesReadTimeout() throws InterruptedException {
        blockResponse = true;
        kakaoAddressClient = client("test-kakao-key", Duration.ofMillis(100));

        assertThat(kakaoAddressClient.findCoordinates(ADDRESS)).isEqualTo(
            new KakaoAddressResult.Failure(KakaoAddressErrorType.NETWORK_ERROR));

        assertThat(requestReceived.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("API 키가 없어도 HTTP 요청을 보내고 인증 오류를 구분한다")
    void sendsRequestWithoutApiKeyAndClassifiesHttpError() {
        responseStatus = 401;
        responseBody = "{\"message\":\"test error\"}";
        kakaoAddressClient = client("", Duration.ofSeconds(1));

        assertThat(kakaoAddressClient.findCoordinates(ADDRESS)).isEqualTo(
            new KakaoAddressResult.Failure(KakaoAddressErrorType.AUTHENTICATION_ERROR));

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
