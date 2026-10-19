package com.homes.zipsai.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.homes.zipsai.building.service.ComplaintAiFeedbackResultService;
import com.homes.zipsai.common.service.AiNotificationQueueConsumer;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnBooleanProperty("app.ai.notification-queue.enabled")
@EnableConfigurationProperties(AiNotificationQueueProperties.class)
public class AiNotificationQueueConfiguration {

    @Bean(destroyMethod = "close")
    SqsClient aiNotificationSqsClient(AiNotificationQueueProperties properties) {
        return SqsClient.builder()
                .region(Region.of(properties.awsRegion()))
                .credentialsProvider(credentialsProvider(properties))
                .build();
    }

    @Bean
    AiNotificationQueueConsumer aiNotificationQueueConsumer(
            SqsClient aiNotificationSqsClient,
            AiNotificationQueueProperties properties,
            ObjectMapper objectMapper,
            ComplaintAiFeedbackResultService complaintAiFeedbackResultService) {
        if (properties.queueUrl() == null || properties.queueUrl().isBlank()) {
            throw new IllegalStateException("AI 처리 결과 통지 큐 URL이 설정되지 않았습니다.");
        }
        return new AiNotificationQueueConsumer(aiNotificationSqsClient, properties, objectMapper,
                complaintAiFeedbackResultService);
    }

    private static AwsCredentialsProvider credentialsProvider(AiNotificationQueueProperties properties) {
        String profile = properties.awsProfile();
        return profile == null || profile.isBlank()
                ? DefaultCredentialsProvider.builder().build()
                : ProfileCredentialsProvider.create(profile);
    }
}
