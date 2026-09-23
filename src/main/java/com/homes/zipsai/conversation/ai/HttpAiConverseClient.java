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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "app.ai.client", havingValue = "http", matchIfMissing = true)
public class HttpAiConverseClient implements AiConverseClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpAiConverseClient.class);

    private final RestClient restClient;
    private final String conversePath;
    private final ObjectMapper objectMapper;

    public HttpAiConverseClient(RestClient.Builder builder, @Value("${app.ai.base-url}") String baseUrl,
                                @Value("${app.ai.converse-path}") String conversePath, ObjectMapper objectMapper) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.conversePath = conversePath;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiConverseResponse converse(AiConverseRequest request) {
        String traceId = request.traceId();
        LOGGER.info("AI 요청. traceId={}, body={}", traceId, request);
        String body = exchange(request, traceId);
        LOGGER.info("AI 응답 원문. traceId={}, body={}", traceId, body);
        try {
            return objectMapper.readValue(body, AiConverseResponse.class);
        } catch (JacksonException e) {
            LOGGER.error("AI 응답을 읽지 못했습니다. traceId={}, body={}", traceId, body, e);
            throw new AiUnavailableException();
        }
    }

    private String exchange(AiConverseRequest request, String traceId) {
        try {
            return restClient.post()
                .uri(conversePath)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (httpRequest, response) -> {
                    throw toApiException(response.getStatusCode(), traceId);
                })
                .body(String.class);
        } catch (RestClientException e) {
            LOGGER.error("AI 서버를 호출하지 못했습니다. traceId={}", traceId, e);
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
