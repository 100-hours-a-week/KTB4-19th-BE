package com.homes.zipsai.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import com.homes.zipsai.global.logging.StructuredLogger;

class AsyncConfigTest {

    TaskExecutor chatbotReplyExecutor = new AsyncConfig().chatbotReplyExecutor(mock(StructuredLogger.class));

    @Test
    @DisplayName("챗봇 답변 실행기는 작업을 이름 붙은 가상 스레드에서 실행한다")
    void runsTaskOnNamedVirtualThread() throws Exception {
        CompletableFuture<Thread> worker = new CompletableFuture<>();

        chatbotReplyExecutor.execute(() -> worker.complete(Thread.currentThread()));

        Thread thread = worker.get(5, TimeUnit.SECONDS);
        assertThat(thread.isVirtual()).isTrue();
        assertThat(thread.getName()).startsWith("chatbot-reply-");
    }
}
