package com.homes.zipsai.conversation.ai;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import com.homes.zipsai.global.exception.TooManyRequestsException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "app.ai.client", havingValue = "http", matchIfMissing = true)
public class HttpAiConverseClient implements AiConverseClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpAiConverseClient.class);
    private static final String DATA_PREFIX = "data:";

    private final RestClient restClient;
    private final String converseStreamPath;
    private final ObjectMapper objectMapper;

    @Autowired
    public HttpAiConverseClient(RestClient.Builder builder, @Value("${app.ai.base-url}") String baseUrl,
                                @Value("${app.ai.converse-stream-path}") String converseStreamPath,
                                @Value("${app.ai.converse-connect-timeout:PT3S}") Duration connectTimeout,
                                @Value("${app.ai.converse-read-timeout:PT60S}") Duration readTimeout,
                                ObjectMapper objectMapper) {
        this(builder.baseUrl(baseUrl).requestFactory(requestFactory(connectTimeout, readTimeout)).build(),
            converseStreamPath, objectMapper);
    }

    HttpAiConverseClient(RestClient restClient, String converseStreamPath, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.converseStreamPath = converseStreamPath;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiConverseResponse converse(AiConverseRequest request, Consumer<String> onReplyDelta) {
        String turnId = request.turnId();
        try {
            LOGGER.info("AI 요청 원문. traceId={}, turnId={}, body={}", MDC.get("traceId"), turnId,
                objectMapper.writeValueAsString(request));
            return exchange(request, turnId, onReplyDelta);
        } catch (JacksonException e) {
            LOGGER.error("AI 응답을 읽지 못했습니다. turnId={}", turnId);
            throw new AiUnavailableException();
        }
    }

    private AiConverseResponse exchange(AiConverseRequest request, String turnId, Consumer<String> onReplyDelta) {
        try {
            return restClient.post()
                .uri(converseStreamPath)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(request)
                .exchange((httpRequest, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw toApiException(response.getStatusCode(), turnId);
                    }
                    return readReply(response.getBody(), turnId, onReplyDelta);
                });
        } catch (RestClientException | UncheckedIOException e) {
            LOGGER.error("AI 서버를 호출하지 못했습니다. turnId={}", turnId);
            throw new AiUnavailableException();
        }
    }

    private AiConverseResponse readReply(InputStream body, String turnId, Consumer<String> onReplyDelta) {
        Iterator<String> lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))
            .lines()
            .filter(line -> line.startsWith(DATA_PREFIX))
            .iterator();
        while (lines.hasNext()) {
            String data = lines.next().substring(DATA_PREFIX.length()).strip();
            AiConverseStreamEvent event = objectMapper.readValue(data, AiConverseStreamEvent.class);
            if (event.isReplyDelta()) {
                onReplyDelta.accept(event.text());
            }
            if (event.isComplete()) {
                LOGGER.info("AI 응답 원문. traceId={}, turnId={}, body={}", MDC.get("traceId"), turnId, data);
                return event.response();
            }
            if (event.isError()) {
                LOGGER.error("AI 서버가 답변 도중 실패했습니다. turnId={}, message={}", turnId, event.message());
                throw new AiUnavailableException();
            }
        }
        LOGGER.error("AI 서버가 최종 응답 없이 스트림을 끝냈습니다. turnId={}", turnId);
        throw new AiUnavailableException();
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

    private static SimpleClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return requestFactory;
    }
}
