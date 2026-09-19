package com.homes.zipsai.conversation.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.homes.zipsai.global.exception.AiUnavailableException;
import com.homes.zipsai.global.exception.ApiException;
import com.homes.zipsai.global.exception.InternalServerException;
import com.homes.zipsai.global.exception.TooManyRequestsException;

@Component
@ConditionalOnProperty(name = "app.ai.client", havingValue = "http", matchIfMissing = true)
public class HttpAiConverseClient implements AiConverseClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpAiConverseClient.class);

    private final RestClient restClient;
    private final String conversePath;

    public HttpAiConverseClient(RestClient.Builder builder, @Value("${app.ai.base-url}") String baseUrl,
                                @Value("${app.ai.converse-path}") String conversePath) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.conversePath = conversePath;
    }

    @Override
    public AiConverseResponse converse(AiConverseRequest request) {
        try {
            return restClient.post()
                .uri(conversePath)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (httpRequest, response) -> {
                    throw toApiException(response.getStatusCode(), request.traceId());
                })
                .body(AiConverseResponse.class);
        } catch (RestClientException e) {
            LOGGER.error("AI 서버를 호출하지 못했습니다. traceId={}", request.traceId(), e);
            throw new AiUnavailableException();
        }
    }

    private ApiException toApiException(HttpStatusCode status, String traceId) {
        LOGGER.error("AI 서버가 오류로 응답했습니다. traceId={}, status={}", traceId, status.value());
        if (status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
            return new TooManyRequestsException();
        }
        if (status.isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE) || status.isSameCodeAs(HttpStatus.GATEWAY_TIMEOUT)) {
            return new AiUnavailableException();
        }
        return new InternalServerException();
    }
}
