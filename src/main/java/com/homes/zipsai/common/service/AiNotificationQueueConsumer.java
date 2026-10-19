package com.homes.zipsai.common.service;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.homes.zipsai.building.service.ComplaintAiFeedbackResultService;
import com.homes.zipsai.common.config.AiNotificationQueueProperties;
import com.homes.zipsai.conversation.ai.AiProcessingNotification;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.ObjectMapper;

public class AiNotificationQueueConsumer implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiNotificationQueueConsumer.class);

    private static final Duration RECEIVE_FAILURE_BACKOFF = Duration.ofSeconds(5);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(25);

    private final SqsClient sqsClient;
    private final AiNotificationQueueProperties properties;
    private final ObjectMapper objectMapper;
    private final ComplaintAiFeedbackResultService complaintAiFeedbackResultService;

    private volatile boolean running;
    private Thread worker;

    public AiNotificationQueueConsumer(SqsClient sqsClient, AiNotificationQueueProperties properties,
                                       ObjectMapper objectMapper,
                                       ComplaintAiFeedbackResultService complaintAiFeedbackResultService) {
        this.sqsClient = sqsClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.complaintAiFeedbackResultService = complaintAiFeedbackResultService;
    }

    @Override
    public void start() {
        running = true;
        worker = Thread.ofVirtual().name("ai-notification-queue").start(this::pollUntilStopped);
        LOGGER.info("AI 처리 결과 통지 큐 수신을 시작합니다. queueUrl={}", properties.queueUrl());
    }

    @Override
    public void stop() {
        running = false;
        if (worker == null) {
            return;
        }
        worker.interrupt();
        try {
            worker.join(STOP_TIMEOUT);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        LOGGER.info("AI 처리 결과 통지 큐 수신을 중지했습니다.");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollUntilStopped() {
        while (running) {
            try {
                pollOnce();
            } catch (RuntimeException exception) {
                if (!running) {
                    return;
                }
                LOGGER.error("AI 처리 결과 통지 큐 수신에 실패했습니다.", exception);
                sleep(RECEIVE_FAILURE_BACKOFF);
            }
        }
    }

    void pollOnce() {
        List<Message> messages = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(properties.queueUrl())
                .maxNumberOfMessages(properties.maxMessages())
                .waitTimeSeconds(properties.waitTimeSeconds())
                .build()).messages();
        for (Message message : messages) {
            if (process(message)) {
                sqsClient.deleteMessage(builder -> builder
                        .queueUrl(properties.queueUrl())
                        .receiptHandle(message.receiptHandle()));
            }
        }
    }

    boolean process(Message message) {
        AiProcessingNotification notification;
        try {
            notification = objectMapper.readValue(message.body(), AiProcessingNotification.class);
        } catch (RuntimeException exception) {
            LOGGER.error("AI 처리 결과 통지를 해석하지 못했습니다. messageId={}", message.messageId(), exception);
            return false;
        }
        if (!complaintAiFeedbackResultService.supports(notification.kind())) {
            LOGGER.info("처리하지 않는 AI 처리 결과 통지입니다. messageId={}, kind={}, traceId={}",
                    message.messageId(), notification.kind(), notification.traceId());
            return true;
        }
        try {
            complaintAiFeedbackResultService.handle(notification);
            return true;
        } catch (RuntimeException exception) {
            LOGGER.error("AI 처리 결과 통지 반영에 실패했습니다. messageId={}, kind={}, traceId={}",
                    message.messageId(), notification.kind(), notification.traceId(), exception);
            return false;
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
