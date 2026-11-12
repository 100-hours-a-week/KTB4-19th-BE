package com.homes.zipsai.conversation.ai;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.homes.zipsai.global.exception.AiUnavailableException;
import com.homes.zipsai.global.exception.ApiException;
import com.homes.zipsai.global.exception.InternalServerException;

@Component
@ConditionalOnProperty(name = "app.ai.client", havingValue = "http")
public class HttpAiIndexingClient implements AiIndexingClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpAiIndexingClient.class);

    private final RestClient restClient;
    private final String indexingPath;
    private final String apiKey;

    @Autowired
    public HttpAiIndexingClient(RestClient.Builder builder,
                                @Value("${app.ai.base-url}") String baseUrl,
                                @Value("${app.ai.indexing-path:/api/v3/ai/indexing/jobs}") String indexingPath,
                                @Value("${app.ai.api-key:}") String apiKey,
                                @Value("${app.ai.indexing-connect-timeout:PT3S}") Duration connectTimeout,
                                @Value("${app.ai.indexing-read-timeout:PT180S}") Duration readTimeout) {
        this(builder.requestFactory(requestFactory(connectTimeout, readTimeout)), baseUrl, indexingPath, apiKey);
    }

    HttpAiIndexingClient(RestClient.Builder builder, String baseUrl, String indexingPath, String apiKey) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.indexingPath = indexingPath;
        this.apiKey = apiKey;
    }

    @Override
    public AiIndexingResponse index(AiIndexingRequest request) {
        try {
            AiIndexingResponse body = restClient.post()
                    .uri(indexingPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (!apiKey.isBlank()) {
                            headers.set("X-API-Key", apiKey);
                        }
                    })
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (httpRequest, response) -> {
                        throw toApiException(response.getStatusCode(), response.getHeaders());
                    })
                    .body(AiIndexingResponse.class);
            return body == null ? AiIndexingResponse.empty() : body;
        } catch (RestClientException exception) {
            LOGGER.error("AI 문서 색인 요청에 실패했습니다. buildingId={}, docId={}",
                    request.buildingId(), request.docId(), exception);
            throw new AiUnavailableException();
        }
    }

    private ApiException toApiException(HttpStatusCode status, HttpHeaders headers) {
        if (status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
            return AiRetryLaterException.inProgress(headers);
        }
        if (status.isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE)) {
            return AiRetryLaterException.from(headers);
        }
        if (status.isSameCodeAs(HttpStatus.GATEWAY_TIMEOUT)) {
            return new AiUnavailableException();
        }
        return new InternalServerException();
    }

    private static SimpleClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return requestFactory;
    }
}
