package com.homes.zipsai.building.client;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@Component
public class KakaoAddressClient {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    private final RestClient client;

    public KakaoAddressClient(
            RestClient.Builder builder,
            @Value("${app.kakao.base-url:https://dapi.kakao.com}") String baseUrl,
            @Value("${app.kakao.api-key:}") String apiKey,
            @Value("${app.kakao.connect-timeout:PT1S}") Duration connectTimeout,
            @Value("${app.kakao.read-timeout:PT2S}") Duration readTimeout
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.client = builder.baseUrl(baseUrl).requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + apiKey).build();
    }

    public Optional<Point> findCoordinates(String roadAddress) {
        try {
            AddressResponse response = client.get().uri(uri -> uri.path("/v2/local/search/address.json")
                    .queryParam("query", "{address}").queryParam("analyze_type", "exact").queryParam("size", 2)
                    .build(roadAddress))
                .retrieve().body(AddressResponse.class);
            if (response == null || response.meta() == null || response.meta().totalCount() != 1
                    || response.documents() == null || response.documents().size() != 1) {
                return Optional.empty();
            }
            AddressDocument document = response.documents().getFirst();
            if (document == null || document.x() == null || document.y() == null
                    || !("ROAD_ADDR".equals(document.addressType()) || "REGION_ADDR".equals(document.addressType()))) {
                return Optional.empty();
            }
            double longitude = Double.parseDouble(document.x());
            double latitude = Double.parseDouble(document.y());
            if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                    || longitude <= -180 || longitude > 180 || latitude < -90 || latitude > 90) {
                return Optional.empty();
            }
            return Optional.of(GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude)));
        } catch (RestClientException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AddressResponse(Meta meta, List<AddressDocument> documents) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Meta(@JsonProperty("total_count") int totalCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AddressDocument(@JsonProperty("address_type") String addressType, String x, String y) {
    }
}
