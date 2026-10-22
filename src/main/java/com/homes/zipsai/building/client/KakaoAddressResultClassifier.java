package com.homes.zipsai.building.client;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class KakaoAddressResultClassifier {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    private KakaoAddressResultClassifier() {
    }

    public static KakaoAddressResult classifyResponse(String body) {
        try {
            JsonNode response = JSON_MAPPER.readTree(body);
            if (response == null || !response.isObject()) {
                return failure(KakaoAddressErrorType.INVALID_RESPONSE);
            }
            JsonNode totalCount = response.path("meta").path("total_count");
            JsonNode documents = response.path("documents");
            if (!totalCount.isIntegralNumber() || !totalCount.canConvertToInt() || totalCount.intValue() < 0
                    || !documents.isArray() || documents.size() > totalCount.intValue()
                    || (totalCount.intValue() > 0 && documents.isEmpty())) {
                return failure(KakaoAddressErrorType.INVALID_RESPONSE);
            }
            if (totalCount.intValue() == 0) {
                return failure(KakaoAddressErrorType.ADDRESS_NOT_FOUND);
            }
            Point coordinates = null;
            for (JsonNode document : documents) {
                coordinates = coordinates(document);
            }
            if (totalCount.intValue() > 1) {
                return failure(KakaoAddressErrorType.AMBIGUOUS_ADDRESS);
            }
            String addressType = documents.get(0).path("address_type").asString();
            if (!"ROAD_ADDR".equals(addressType) && !"REGION_ADDR".equals(addressType)) {
                return failure(KakaoAddressErrorType.NON_BUILDING_ADDRESS);
            }
            return new KakaoAddressResult.Success(coordinates);
        } catch (JacksonException | IllegalArgumentException exception) {
            return failure(KakaoAddressErrorType.INVALID_RESPONSE);
        }
    }

    public static KakaoAddressResult.Failure classifyException(RuntimeException exception) {
        if (exception instanceof RestClientResponseException responseException) {
            return classifyHttpError(responseException);
        }
        if (exception instanceof ResourceAccessException) {
            return failure(KakaoAddressErrorType.NETWORK_ERROR);
        }
        if (exception instanceof JacksonException) {
            return failure(KakaoAddressErrorType.INVALID_RESPONSE);
        }
        return failure(KakaoAddressErrorType.UNKNOWN_ERROR);
    }

    private static Point coordinates(JsonNode document) {
        JsonNode addressType = document.path("address_type");
        JsonNode longitudeNode = document.path("x");
        JsonNode latitudeNode = document.path("y");
        if (!addressType.isString() || addressType.asString().isBlank()
                || !longitudeNode.isString() || !latitudeNode.isString()) {
            throw new IllegalArgumentException("Missing address type or coordinates");
        }
        double longitude = Double.parseDouble(longitudeNode.asString());
        double latitude = Double.parseDouble(latitudeNode.asString());
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                || longitude <= -180 || longitude > 180 || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Invalid coordinates");
        }
        return GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
    }

    private static KakaoAddressResult.Failure classifyHttpError(RestClientResponseException exception) {
        JsonNode code = errorCode(exception.getResponseBodyAsString());
        if (code != null && code.isIntegralNumber() && code.canConvertToInt()) {
            return failure(switch (code.intValue()) {
                case -1, -7, -9798 -> KakaoAddressErrorType.KAKAO_SERVER_ERROR;
                case -603 -> KakaoAddressErrorType.NETWORK_ERROR;
                case -2, -8 -> KakaoAddressErrorType.REQUEST_CONFIGURATION_ERROR;
                case -3, -5 -> KakaoAddressErrorType.PERMISSION_ERROR;
                case -4, -6, -9, -12, -13 -> KakaoAddressErrorType.APP_STATE_ERROR;
                case -10, -11 -> KakaoAddressErrorType.QUOTA_EXCEEDED;
                case -401, -903 -> KakaoAddressErrorType.AUTHENTICATION_ERROR;
                default -> KakaoAddressErrorType.UNKNOWN_HTTP_ERROR;
            });
        }
        int status = exception.getStatusCode().value();
        if (status >= 500 && status <= 599) {
            return failure(KakaoAddressErrorType.KAKAO_SERVER_ERROR);
        }
        return failure(switch (status) {
            case 401 -> KakaoAddressErrorType.AUTHENTICATION_ERROR;
            case 403 -> KakaoAddressErrorType.PERMISSION_ERROR;
            case 429 -> KakaoAddressErrorType.QUOTA_EXCEEDED;
            default -> KakaoAddressErrorType.UNKNOWN_HTTP_ERROR;
        });
    }

    private static JsonNode errorCode(String body) {
        try {
            JsonNode response = JSON_MAPPER.readTree(body);
            return response == null ? null : response.path("code");
        } catch (JacksonException | IllegalArgumentException exception) {
            return null;
        }
    }

    private static KakaoAddressResult.Failure failure(KakaoAddressErrorType errorType) {
        return new KakaoAddressResult.Failure(errorType);
    }
}
