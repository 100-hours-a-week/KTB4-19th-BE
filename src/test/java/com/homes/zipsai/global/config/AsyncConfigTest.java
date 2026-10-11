package com.homes.zipsai.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskExecutor;

class AsyncConfigTest {

    TaskExecutor chatbotReplyExecutor = new AsyncConfig().chatbotReplyExecutor();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("챗봇 답변 실행기는 작업을 이름 붙은 가상 스레드에서 실행한다")
    void runsTaskOnNamedVirtualThread() throws Exception {
        CompletableFuture<Thread> worker = new CompletableFuture<>();

        chatbotReplyExecutor.execute(() -> worker.complete(Thread.currentThread()));

        Thread thread = worker.get(5, TimeUnit.SECONDS);
        assertThat(thread.isVirtual()).isTrue();
        assertThat(thread.getName()).startsWith("chatbot-reply-");
    }

    @Test
    @DisplayName("챗봇 답변 실행기는 작업을 맡긴 요청의 traceId를 넘겨받는다")
    void copiesCallerMdcToTask() throws Exception {
        CompletableFuture<String> traceId = new CompletableFuture<>();
        MDC.put("traceId", "trace-123");

        chatbotReplyExecutor.execute(() -> traceId.complete(MDC.get("traceId")));

        assertThat(traceId.get(5, TimeUnit.SECONDS)).isEqualTo("trace-123");
    }
}
