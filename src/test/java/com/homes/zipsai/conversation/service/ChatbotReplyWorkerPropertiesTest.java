package com.homes.zipsai.conversation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ChatbotReplyWorkerPropertiesTest {

    @Test
    @DisplayName("별도 설정이 없으면 시도 상한 130초와 전체 요청 상한 280초를 사용한다")
    void bindsDefaultTimeouts() {
        ChatbotReplyWorkerProperties properties = bind(Map.of());

        assertThat(properties.attemptTimeout()).isEqualTo(Duration.ofSeconds(130));

        assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(280));
    }

    @Test
    @DisplayName("AI와 사용자 환경 변화에 맞춰 제한시간을 설정으로 변경할 수 있다")
    void overridesTimeoutsFromConfiguration() {
        ChatbotReplyWorkerProperties properties = bind(Map.of(
            "app.conversation.reply-worker.attempt-timeout", "180s",
            "app.conversation.reply-worker.request-timeout", "400s"));

        assertThat(properties.attemptTimeout()).isEqualTo(Duration.ofSeconds(180));

        assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(400));
    }

    private static ChatbotReplyWorkerProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
            .bindOrCreate("app.conversation.reply-worker", Bindable.of(ChatbotReplyWorkerProperties.class));
    }
}
