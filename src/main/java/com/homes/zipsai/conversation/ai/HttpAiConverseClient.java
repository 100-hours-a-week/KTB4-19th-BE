package com.homes.zipsai.conversation.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import com.homes.zipsai.global.logging.StructuredLogger;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "app.ai.client", havingValue = "http", matchIfMissing = true)
public class HttpAiConverseClient implements AiConverseClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpAiConverseClient.class);

    private final RestClient restClient;
    private final String conversePath;
    private final ObjectMapper objectMapper;
    private final StructuredLogger structuredLogger;

    public HttpAiConverseClient(RestClient.Builder builder, @Value("${app.ai.base-url}") String baseUrl,
                                @Value("${app.ai.converse-path}") String conversePath, ObjectMapper objectMapper,
                                StructuredLogger structuredLogger) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.conversePath = conversePath;
        this.objectMapper = objectMapper;
        this.structuredLogger = structuredLogger;
    }

    @Override
    public AiConverseResponse converse(AiConverseRequest request) {
        String turnId = request.turnId();
        String traceId = MDC.get("traceId");
        long started = System.nanoTime();
        String outcome = "ok";
        String errorCode = null;
        try {
            LOGGER.info("AI 요청 원문. traceId={}, turnId={}, body={}", traceId, turnId,
                objectMapper.writeValueAsString(request));
            String body = exchange(request, turnId);
            LOGGER.info("AI 응답 원문. traceId={}, turnId={}, body={}", traceId, turnId, body);
            return read(body, turnId);
        } catch (RuntimeException e) {
            outcome = "fail";
            errorCode = e instanceof ApiException apiException ? apiException.code : "AI_UNAVAILABLE";
            throw e;
        } finally {
            if (traceId != null) {
                structuredLogger.stageDone(traceId, conversePath, "ai_api",
                    (System.nanoTime() - started) / 1_000_000, outcome, errorCode);
            }
        }
    }

    private AiConverseResponse read(String body, String turnId) {
        try {
            return objectMapper.readValue(body, AiConverseResponse.class);
        } catch (JacksonException e) {
            LOGGER.error("AI 응답을 읽지 못했습니다. turnId={}, body={}", turnId, body);
            throw new AiUnavailableException();
        }
    }

    private String exchange(AiConverseRequest request, String turnId) {
        try {
            return restClient.post()
                .uri(conversePath)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (httpRequest, response) -> {
                    throw toApiException(response.getStatusCode(), turnId);
                })
                .body(String.class);
        } catch (RestClientException e) {
            LOGGER.error("AI 서버를 호출하지 못했습니다. turnId={}", turnId);
            throw new AiUnavailableException();
        }
    }

    private ApiException toApiException(HttpStatusCode status, String turnId) {
        LOGGER.error("AI 서버가 오류로 응답했습니다. turnId={}, status={}", turnId, status.value());
        if (status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
            return new TooManyRequestsException();
        }
        if (status.isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE) || status.isSameCodeAs(HttpStatus.GATEWAY_TIMEOUT)) {
            return new AiUnavailableException();
        }
        return new InternalServerException();
    }
}
