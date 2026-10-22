package com.homes.zipsai.building.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Point;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

class KakaoAddressResultClassifierTest {

    private static final String DOCUMENT =
        "{\"address_type\":\"ROAD_ADDR\",\"x\":\"127.0276\",\"y\":\"37.4979\"}";

    @ParameterizedTest
    @DisplayName("유효한 건물 주소 한 건은 경도와 위도를 유지한 WGS 84 좌표로 분류한다")
    @ValueSource(strings = {"ROAD_ADDR", "REGION_ADDR"})
    void classifiesUniqueBuildingAddressAsSuccess(String addressType) {
        KakaoAddressResult result = KakaoAddressResultClassifier.classifyResponse(
            response("1", DOCUMENT.replace("ROAD_ADDR", addressType)));

        assertThat(result).isInstanceOf(KakaoAddressResult.Success.class);

        Point coordinates = ((KakaoAddressResult.Success) result).coordinates();

        assertThat(coordinates.getX()).isEqualTo(127.0276);

        assertThat(coordinates.getY()).isEqualTo(37.4979);

        assertThat(coordinates.getSRID()).isEqualTo(4326);
    }

    @Test
    @DisplayName("검색 결과가 정상적으로 0건이면 재시도하지 않는 주소 오류로 분류하고 회차는 계속한다")
    void classifiesNormalEmptyResultAsAddressError() {
        assertFailure(KakaoAddressResultClassifier.classifyResponse(response("0", "")),
            KakaoAddressErrorType.ADDRESS_NOT_FOUND, KakaoAddressErrorType.RetryPolicy.NONE, false);
    }

    @ParameterizedTest
    @DisplayName("전체 검색 결과가 여러 건이면 일부 문서만 반환되어도 주소 불명확으로 분류한다")
    @ValueSource(ints = {1, 2})
    void classifiesMultipleResultsAsAmbiguousAddress(int returnedCount) {
        String documents = returnedCount == 1 ? DOCUMENT : DOCUMENT + "," + DOCUMENT;

        assertFailure(KakaoAddressResultClassifier.classifyResponse(response("2", documents)),
            KakaoAddressErrorType.AMBIGUOUS_ADDRESS, KakaoAddressErrorType.RetryPolicy.NONE, false);
    }

    @ParameterizedTest
    @DisplayName("건물 주소가 아닌 결과는 재시도하지 않고 해당 작업만 종료할 수 있도록 분류한다")
    @ValueSource(strings = {"REGION", "ROAD", "UNKNOWN"})
    void classifiesNonBuildingAddressAsTerminal(String addressType) {
        assertFailure(KakaoAddressResultClassifier.classifyResponse(
                response("1", DOCUMENT.replace("ROAD_ADDR", addressType))),
            KakaoAddressErrorType.NON_BUILDING_ADDRESS, KakaoAddressErrorType.RetryPolicy.NONE, false);
    }

    @ParameterizedTest
    @DisplayName("필수 필드 누락과 잘못된 구조는 정상 0건과 구분해 무제한 재시도 및 회차 중단으로 분류한다")
    @NullAndEmptySource
    @ValueSource(strings = {
        " ", "{broken", "null", "{}", "[]", "true",
        "{\"meta\":{},\"documents\":[]}",
        "{\"meta\":{\"total_count\":null},\"documents\":[]}",
        "{\"meta\":{\"total_count\":\"0\"},\"documents\":[]}",
        "{\"meta\":{\"total_count\":0.5},\"documents\":[]}",
        "{\"meta\":{\"total_count\":-1},\"documents\":[]}",
        "{\"meta\":{\"total_count\":2147483648},\"documents\":[]}",
        "{\"meta\":{\"total_count\":0}}",
        "{\"meta\":{\"total_count\":0},\"documents\":null}",
        "{\"meta\":{\"total_count\":0},\"documents\":{}}",
        "{\"meta\":{\"total_count\":1},\"documents\":[]}",
        "{\"meta\":{\"total_count\":2},\"documents\":[null,null]}",
        "{\"meta\":{\"total_count\":0},\"documents\":[{}]}",
        "{\"meta\":{\"total_count\":1},\"documents\":[null]}",
        "{\"meta\":{\"total_count\":1},\"documents\":[{}]}",
        "{\"meta\":{\"total_count\":0},\"documents\":[]} {}"
    })
    void classifiesMalformedResponseAsUnlimitedRetry(String body) {
        assertFailure(KakaoAddressResultClassifier.classifyResponse(body),
            KakaoAddressErrorType.INVALID_RESPONSE, KakaoAddressErrorType.RetryPolicy.UNLIMITED, true);
    }

    @ParameterizedTest
    @DisplayName("주소 유형이나 좌표가 누락되거나 자료형이 다르면 응답 형식 이상으로 분류한다")
    @ValueSource(strings = {
        "{\"x\":\"127\",\"y\":\"37\"}",
        "{\"address_type\":null,\"x\":\"127\",\"y\":\"37\"}",
        "{\"address_type\":\" \",\"x\":\"127\",\"y\":\"37\"}",
        "{\"address_type\":1,\"x\":\"127\",\"y\":\"37\"}",
        "{\"address_type\":\"ROAD_ADDR\",\"y\":\"37\"}",
        "{\"address_type\":\"ROAD_ADDR\",\"x\":\"127\"}",
        "{\"address_type\":\"ROAD_ADDR\",\"x\":null,\"y\":\"37\"}",
        "{\"address_type\":\"ROAD_ADDR\",\"x\":127,\"y\":37}",
        "{\"address_type\":\"ROAD_ADDR\",\"x\":{},\"y\":\"37\"}"
    })
    void classifiesMissingOrMistypedDocumentFieldsAsMalformed(String document) {
        assertFailure(KakaoAddressResultClassifier.classifyResponse(response("1", document)),
            KakaoAddressErrorType.INVALID_RESPONSE, KakaoAddressErrorType.RetryPolicy.UNLIMITED, true);
    }

    @ParameterizedTest
    @DisplayName("숫자가 아니거나 유한하지 않거나 저장 범위를 벗어난 좌표는 응답 형식 이상으로 분류한다")
    @CsvSource({"bad,37", "NaN,37", "Infinity,37", "127,NaN", "181,37", "-180,37", "127,91", "127,-91"})
    void classifiesInvalidCoordinatesAsMalformed(String longitude, String latitude) {
        String document = DOCUMENT.replace("127.0276", longitude).replace("37.4979", latitude);

        assertFailure(KakaoAddressResultClassifier.classifyResponse(response("1", document)),
            KakaoAddressErrorType.INVALID_RESPONSE, KakaoAddressErrorType.RetryPolicy.UNLIMITED, true);
    }

    @ParameterizedTest
    @DisplayName("MySQL에 저장 가능한 경도와 위도의 경계값은 성공으로 분류한다")
    @CsvSource({"180,90", "180,-90", "-179.999999,0"})
    void acceptsValidCoordinateBoundaries(String longitude, String latitude) {
        String document = DOCUMENT.replace("127.0276", longitude).replace("37.4979", latitude);

        assertThat(KakaoAddressResultClassifier.classifyResponse(response("1", document)))
            .isInstanceOf(KakaoAddressResult.Success.class);
    }

    @ParameterizedTest
    @DisplayName("카카오 오류 코드는 HTTP 400이어도 실제 원인에 따라 제한 또는 무제한 재시도로 분류한다")
    @CsvSource({
        "-1, KAKAO_SERVER_ERROR, LIMITED", "-7, KAKAO_SERVER_ERROR, LIMITED",
        "-603, NETWORK_ERROR, LIMITED", "-9798, KAKAO_SERVER_ERROR, LIMITED",
        "-2, REQUEST_CONFIGURATION_ERROR, UNLIMITED", "-8, REQUEST_CONFIGURATION_ERROR, UNLIMITED",
        "-3, PERMISSION_ERROR, UNLIMITED", "-5, PERMISSION_ERROR, UNLIMITED",
        "-4, APP_STATE_ERROR, UNLIMITED", "-6, APP_STATE_ERROR, UNLIMITED",
        "-9, APP_STATE_ERROR, UNLIMITED", "-12, APP_STATE_ERROR, UNLIMITED",
        "-13, APP_STATE_ERROR, UNLIMITED", "-10, QUOTA_EXCEEDED, UNLIMITED",
        "-11, QUOTA_EXCEEDED, UNLIMITED", "-401, AUTHENTICATION_ERROR, UNLIMITED",
        "-903, AUTHENTICATION_ERROR, UNLIMITED"
    })
    void classifiesKnownKakaoErrorCodes(int code, KakaoAddressErrorType error,
                                      KakaoAddressErrorType.RetryPolicy policy) {
        assertFailure(KakaoAddressResultClassifier.classifyException(httpError(400, "{\"code\":" + code + "}")),
            error, policy, true);
    }

    @ParameterizedTest
    @DisplayName("오류 코드가 없어도 명확한 HTTP 상태는 해당 오류 정책으로 분류한다")
    @CsvSource({
        "401, AUTHENTICATION_ERROR, UNLIMITED",
        "403, PERMISSION_ERROR, UNLIMITED", "429, QUOTA_EXCEEDED, UNLIMITED",
        "500, KAKAO_SERVER_ERROR, LIMITED", "502, KAKAO_SERVER_ERROR, LIMITED",
        "503, KAKAO_SERVER_ERROR, LIMITED", "504, KAKAO_SERVER_ERROR, LIMITED"
    })
    void classifiesHttpStatusWithoutErrorCode(int status, KakaoAddressErrorType error,
                                            KakaoAddressErrorType.RetryPolicy policy) {
        assertFailure(KakaoAddressResultClassifier.classifyException(httpError(status, "not-json")),
            error, policy, true);
    }

    @ParameterizedTest
    @DisplayName("알 수 없는 HTTP 오류는 주소 문제로 추정하지 않고 최종 실패 및 회차 중단으로 분류한다")
    @ValueSource(ints = {404, 405, 418})
    void classifiesUnknownHttpStatusAsTerminal(int status) {
        assertFailure(KakaoAddressResultClassifier.classifyException(httpError(status, "{}")),
            KakaoAddressErrorType.UNKNOWN_HTTP_ERROR, KakaoAddressErrorType.RetryPolicy.NONE, true);
    }

    @Test
    @DisplayName("알 수 없는 카카오 오류 코드는 HTTP 400 요청 설정 오류로 덮어쓰지 않는다")
    void preservesUnknownKakaoErrorCodeAsTerminal() {
        assertFailure(KakaoAddressResultClassifier.classifyException(httpError(400, "{\"code\":-999999}")),
            KakaoAddressErrorType.UNKNOWN_HTTP_ERROR, KakaoAddressErrorType.RetryPolicy.NONE, true);
    }

    @ParameterizedTest
    @DisplayName("원인을 확인할 수 없는 HTTP 400은 요청 설정 오류로 추정하지 않고 최종 실패로 분류한다")
    @NullAndEmptySource
    @ValueSource(strings = {
        "not-json", "{}", "{\"code\":null}", "{\"code\":\"-1\"}",
        "{\"code\":1.5}", "{\"code\":2147483648}"
    })
    void classifiesUnidentifiedBadRequestAsTerminal(String body) {
        assertFailure(KakaoAddressResultClassifier.classifyException(httpError(400, body)),
            KakaoAddressErrorType.UNKNOWN_HTTP_ERROR, KakaoAddressErrorType.RetryPolicy.NONE, true);
    }

    @ParameterizedTest
    @DisplayName("연결 실패와 타임아웃은 횟수를 제한하는 네트워크 오류 및 회차 중단으로 분류한다")
    @MethodSource("networkFailures")
    void classifiesNetworkFailuresAsLimitedRetry(IOException cause) {
        assertFailure(KakaoAddressResultClassifier.classifyException(new ResourceAccessException("network", cause)),
            KakaoAddressErrorType.NETWORK_ERROR, KakaoAddressErrorType.RetryPolicy.LIMITED, true);
    }

    @ParameterizedTest
    @DisplayName("원인을 분류하지 못한 내부 오류는 네트워크나 주소 문제로 추정하지 않고 종료한다")
    @MethodSource("unknownFailures")
    void classifiesUnknownFailureAsTerminal(RuntimeException exception) {
        assertFailure(KakaoAddressResultClassifier.classifyException(exception),
            KakaoAddressErrorType.UNKNOWN_ERROR, KakaoAddressErrorType.RetryPolicy.NONE, false);
    }

    private static Stream<Arguments> networkFailures() {
        return Stream.of(new SocketTimeoutException("timeout"), new ConnectException("refused"),
            new UnknownHostException("dns"), new IOException("connection closed")).map(Arguments::of);
    }

    private static Stream<Arguments> unknownFailures() {
        return Stream.of(new IllegalStateException("unexpected"), new IllegalArgumentException("unexpected"),
            new RestClientException("unexpected")).map(Arguments::of);
    }

    private static String response(String totalCount, String documents) {
        return "{\"meta\":{\"total_count\":" + totalCount + "},\"documents\":[" + documents + "]}";
    }

    private static RestClientResponseException httpError(int status, String body) {
        return new RestClientResponseException("Kakao error", status, "error", HttpHeaders.EMPTY,
            body == null ? null : body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static void assertFailure(KakaoAddressResult result, KakaoAddressErrorType error,
                                      KakaoAddressErrorType.RetryPolicy policy, boolean stopsRound) {
        assertThat(result).isInstanceOf(KakaoAddressResult.Failure.class);

        KakaoAddressErrorType actual = ((KakaoAddressResult.Failure) result).errorType();

        assertThat(actual).isEqualTo(error);

        assertThat(actual.getRetryPolicy()).isEqualTo(policy);

        assertThat(actual.isStopsRound()).isEqualTo(stopsRound);
    }
}
