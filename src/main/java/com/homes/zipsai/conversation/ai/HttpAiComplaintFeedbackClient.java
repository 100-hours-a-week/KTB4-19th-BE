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

@Component
@ConditionalOnProperty(name = "app.ai.client", havingValue = "http")
public class HttpAiComplaintFeedbackClient implements AiComplaintFeedbackClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpAiComplaintFeedbackClient.class);

    private final RestClient restClient;
    private final String feedbackPath;
    private final String apiKey;

    public HttpAiComplaintFeedbackClient(
            RestClient.Builder builder,
            @Value("${app.ai.base-url}") String baseUrl,
            @Value("${app.ai.complaint-feedback-path:/api/v2/ai/indexing/comment}") String feedbackPath,
            @Value("${app.ai.api-key:}") String apiKey) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.feedbackPath = feedbackPath;
        this.apiKey = apiKey;
    }

    @Override
    public void send(AiComplaintFeedbackBatchRequest request) {
        try {
            restClient.post()
                    .uri(feedbackPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (!apiKey.isBlank()) {
                            headers.set("X-API-Key", apiKey);
                        }
                    })
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (httpRequest, response) -> {
                        LOGGER.error("AI가 민원 답변 피드백 접수를 거절했습니다. traceId={}, items={}, status={}",
                                request.traceId(), request.items().size(), response.getStatusCode().value());
                        throw toApiException(response.getStatusCode());
                    })
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            LOGGER.error("AI 민원 답변 피드백 전송에 실패했습니다. traceId={}, items={}",
                    request.traceId(), request.items().size(), exception);
            throw new AiUnavailableException();
        }
    }

    private ApiException toApiException(HttpStatusCode status) {
        if (status.isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE)
                || status.isSameCodeAs(HttpStatus.GATEWAY_TIMEOUT)
                || status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
            return new AiUnavailableException();
        }
        return new InternalServerException();
    }
}
