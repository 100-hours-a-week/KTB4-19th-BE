package com.homes.zipsai.building.client;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class KakaoAddressClient {

    private final RestClient restClient;

    public KakaoAddressClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.kakao.base-url:https://dapi.kakao.com}") String baseUrl,
            @Value("${app.kakao.api-key:}") String apiKey,
            @Value("${app.kakao.connect-timeout:PT1S}") Duration connectTimeout,
            @Value("${app.kakao.read-timeout:PT2S}") Duration readTimeout
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = restClientBuilder.baseUrl(baseUrl).requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + apiKey).build();
    }

    public KakaoAddressResult findCoordinates(String roadAddress) {
        try {
            String response = restClient.get().uri(uri -> uri.path("/v2/local/search/address.json")
                    .queryParam("query", "{address}").queryParam("analyze_type", "exact").queryParam("size", 2)
                    .build(roadAddress))
                .retrieve().body(String.class);
            return KakaoAddressResultClassifier.classifyResponse(response);
        } catch (RuntimeException exception) {
            return KakaoAddressResultClassifier.classifyException(exception);
        }
    }
}
