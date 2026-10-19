package com.homes.zipsai.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.homes.zipsai.building.service.ComplaintAiFeedbackResultService;
import com.homes.zipsai.common.config.AiNotificationQueueProperties;
import com.homes.zipsai.conversation.ai.AiProcessingNotification;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class AiNotificationQueueConsumerTest {

    private static final String QUEUE_URL = "https://sqs.ap-northeast-2.amazonaws.com/000000000000/ai-notifications";
    private static final String UPSERT_FAILED = """
            {
              "kind": "comment_upsert",
              "trace_id": "trace-1",
              "results": [
                { "complaint_detail_id": 9001, "updated_at": "2026-10-15T00:00:12.345678+09:00",
                  "status": "failed", "reason_code": "EMBEDDING_FAILED" }
              ]
            }
            """;

    @Mock
    SqsClient sqsClient;

    @Mock
    ComplaintAiFeedbackResultService resultService;

    AiNotificationQueueConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new AiNotificationQueueConsumer(sqsClient,
            new AiNotificationQueueProperties(true, QUEUE_URL, null, null, 0, 0),
            JsonMapper.builder().build(), resultService);
    }

    @Test
    @DisplayName("답변 처리 결과 통지를 해석해 반영한다")
    void handlesCommentNotification() {
        given(resultService.supports("comment_upsert")).willReturn(true);
        ArgumentCaptor<AiProcessingNotification> captor = ArgumentCaptor.forClass(AiProcessingNotification.class);

        boolean processed = consumer.process(message(UPSERT_FAILED));

        assertThat(processed).isTrue();
        then(resultService).should().handle(captor.capture());
        AiProcessingNotification notification = captor.getValue();
        assertThat(notification.traceId()).isEqualTo("trace-1");
        assertThat(notification.results()).singleElement().satisfies(result -> {
            assertThat(result.complaintDetailId()).isEqualTo(9001L);
            assertThat(result.status()).isEqualTo("failed");
            assertThat(result.reasonCode()).isEqualTo("EMBEDDING_FAILED");
            assertThat(result.updatedAt().withOffsetSameInstant(ZoneOffset.ofHours(9)).toLocalDateTime())
                .isEqualTo(LocalDateTime.of(2026, 10, 15, 0, 0, 12, 345_678_000));
        });
    }

    @Test
    @DisplayName("처리하지 않는 종류의 통지는 반영 없이 처리 완료로 본다")
    void skipsUnsupportedKind() {
        given(resultService.supports("document_index")).willReturn(false);

        boolean processed = consumer.process(message("""
                {"kind": "document_index", "trace_id": "trace-2", "results": [{"doc_id": 1, "building_id": 2}]}
                """));

        assertThat(processed).isTrue();
        then(resultService).should(never()).handle(any());
    }

    @Test
    @DisplayName("해석할 수 없는 통지는 삭제하지 않고 재전달에 맡긴다")
    void keepsMalformedMessage() {
        boolean processed = consumer.process(message("not-json"));

        assertThat(processed).isFalse();
        then(resultService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("반영 중 실패하면 삭제하지 않고 재전달에 맡긴다")
    void keepsMessageWhenHandlingFails() {
        given(resultService.supports("comment_upsert")).willReturn(true);
        willThrow(new IllegalStateException("db down")).given(resultService).handle(any());

        assertThat(consumer.process(message(UPSERT_FAILED))).isFalse();
    }

    @Test
    @DisplayName("반영에 성공한 메시지만 큐에서 삭제한다")
    @SuppressWarnings("unchecked")
    void deletesOnlyProcessedMessages() {
        given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).willReturn(ReceiveMessageResponse.builder()
            .messages(message(UPSERT_FAILED, "ok-receipt"), message("not-json", "bad-receipt"))
            .build());
        given(resultService.supports("comment_upsert")).willReturn(true);

        consumer.pollOnce();

        ArgumentCaptor<Consumer<DeleteMessageRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        then(sqsClient).should(times(1)).deleteMessage(captor.capture());
        DeleteMessageRequest.Builder builder = DeleteMessageRequest.builder();
        captor.getValue().accept(builder);
        assertThat(builder.build().receiptHandle()).isEqualTo("ok-receipt");
        assertThat(builder.build().queueUrl()).isEqualTo(QUEUE_URL);
    }

    @Test
    @DisplayName("큐 수신은 롱 폴링과 최대 수신 개수 설정을 따른다")
    void receivesWithLongPolling() {
        given(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
            .willReturn(ReceiveMessageResponse.builder().build());
        ArgumentCaptor<ReceiveMessageRequest> captor = ArgumentCaptor.forClass(ReceiveMessageRequest.class);

        consumer.pollOnce();

        then(sqsClient).should().receiveMessage(captor.capture());
        assertThat(captor.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(captor.getValue().waitTimeSeconds()).isEqualTo(20);
        assertThat(captor.getValue().maxNumberOfMessages()).isEqualTo(10);
    }

    private static Message message(String body) {
        return message(body, "receipt");
    }

    private static Message message(String body, String receiptHandle) {
        return Message.builder().messageId("message-1").body(body).receiptHandle(receiptHandle).build();
    }
}
