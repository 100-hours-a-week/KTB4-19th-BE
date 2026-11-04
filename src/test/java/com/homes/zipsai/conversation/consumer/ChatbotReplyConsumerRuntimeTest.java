package com.homes.zipsai.conversation.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.LifecycleAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.common.service.RedisMessagePublisher;
import com.homes.zipsai.conversation.ai.AiConverseClient;
import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.ai.AiConverseResponse;
import com.homes.zipsai.conversation.ai.AiRoute;
import com.homes.zipsai.conversation.config.ChatbotReplyPubSubConfiguration;
import com.homes.zipsai.conversation.config.ChatbotReplyPubSubProperties;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;
import com.homes.zipsai.conversation.dto.response.ConversationReplyResponse;
import com.homes.zipsai.conversation.outbox.ChatbotReplyRelayWorker;
import com.homes.zipsai.conversation.outbox.ChatbotReplyRequestSender;
import com.homes.zipsai.conversation.outbox.ChatbotReplyWorker;
import com.homes.zipsai.conversation.outbox.ChatbotReplyWorkerProperties;
import com.homes.zipsai.conversation.repository.ChatbotReplyOutboxRepository;
import com.homes.zipsai.conversation.scheduler.ChatbotReplyRelayScheduler;
import com.homes.zipsai.conversation.scheduler.ChatbotReplyScheduler;
import com.homes.zipsai.conversation.service.ChatbotReplyShutdownSignal;
import com.homes.zipsai.conversation.service.ConversationReplyLock;
import com.homes.zipsai.conversation.service.ConversationReplyService;
import com.homes.zipsai.conversation.service.ConversationService;
import com.homes.zipsai.conversation.service.PendingAiReply;
import com.homes.zipsai.global.logging.StructuredLogger;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.Envelope;

import tools.jackson.databind.ObjectMapper;

@Timeout(20)
class ChatbotReplyConsumerRuntimeTest {

    private static final String QUEUE = "test.chatbot.reply.request";
    private static final String CONSUMER_TAG = "test-chatbot-consumer";
    private static final long EVENT_ID = 900L;
    private static final long DELIVERY_TAG = 37L;

    private final ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
    private final Connection connection = mock(Connection.class);
    private final Channel channel = mock(Channel.class);
    private final ChatbotReplyWorker worker = mock(ChatbotReplyWorker.class);
    private final ChatbotReplyRelayWorker relayWorker = mock(ChatbotReplyRelayWorker.class);
    private final ConversationService conversationService = mock(ConversationService.class);
    private final AiConverseClient aiConverseClient = mock(AiConverseClient.class);
    private final ChatbotReplyOutboxRepository outboxRepository = mock(ChatbotReplyOutboxRepository.class);
    private final ChatbotReplyShutdownSignal shutdownSignal = new ChatbotReplyShutdownSignal();
    private final AtomicReference<Consumer> receiver = new AtomicReference<>();
    private final CountDownLatch subscribed = new CountDownLatch(1);
    private final CountDownLatch canceled = new CountDownLatch(1);
    private final ConversationReplyService replyService = new ConversationReplyService(conversationService,
        aiConverseClient, mock(RedisMessagePublisher.class), new ChatbotReplyPubSubProperties("test:reply"),
        mock(ConversationReplyLock.class), shutdownSignal, mock(StructuredLogger.class), outboxRepository,
        mock(ChatbotReplyRequestSender.class));
    private final ChatbotReplyRequestListener listener = new ChatbotReplyRequestListener(worker, replyService,
        conversationService, outboxRepository, new ObjectMapper(), mock(StructuredLogger.class),
        new ChatbotReplyWorkerProperties(Duration.ofSeconds(130), Duration.ofSeconds(280)));

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> {
            ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(
                (BeanDefinitionRegistry) context.getBeanFactory(), true, context.getEnvironment(), context);
            scanner.addExcludeFilter(new AssignableTypeFilter(ChatbotReplyRequestListener.class));
            scanner.addExcludeFilter(new AssignableTypeFilter(ChatbotReplyPubSubConfiguration.class));
            scanner.scan(ChatbotReplyRequestListener.class.getPackageName(),
                ChatbotReplyPubSubConfiguration.class.getPackageName());
        })
        .withConfiguration(AutoConfigurations.of(LifecycleAutoConfiguration.class))
        .withBean(ConnectionFactory.class, () -> connectionFactory)
        .withBean(ChatbotReplyRequestListener.class, () -> listener)
        .withBean(ChatbotReplyWorker.class, () -> worker)
        .withBean(ChatbotReplyRelayWorker.class, () -> relayWorker)
        .withBean(ChatbotReplyShutdownSignal.class, () -> shutdownSignal)
        .withUserConfiguration(ChatbotReplyScheduler.class, ChatbotReplyRelayScheduler.class)
        .withPropertyValues("app.conversation.reply-consumer.queue=" + QUEUE,
            "app.conversation.reply-worker.enabled=true");

    @BeforeEach
    void setUpBroker() throws Exception {
        given(connectionFactory.createConnection()).willReturn(connection);
        given(connection.isOpen()).willReturn(true);
        given(connection.createChannel(anyBoolean())).willReturn(channel);
        given(channel.isOpen()).willReturn(true);
        given(channel.queueDeclarePassive(QUEUE)).willReturn(mock(AMQP.Queue.DeclareOk.class));
        doAnswer(invocation -> {
            Consumer consumer = invocation.getArgument(6);
            receiver.set(consumer);
            consumer.handleConsumeOk(CONSUMER_TAG);
            subscribed.countDown();
            return CONSUMER_TAG;
        }).when(channel).basicConsume(eq(QUEUE), eq(false), anyString(), anyBoolean(), anyBoolean(), any(), any());
        doAnswer(invocation -> {
            receiver.get().handleCancelOk(CONSUMER_TAG);
            canceled.countDown();
            return null;
        }).when(channel).basicCancel(CONSUMER_TAG);
    }

    @Test
    @DisplayName("기본 API 실행은 요청 큐를 구독하지 않는다")
    void doesNotSubscribeByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context).doesNotHaveBean(AbstractMessageListenerContainer.class);

            then(connectionFactory).shouldHaveNoInteractions();
        });
    }

    @Test
    @DisplayName("Relay 실행에서 Consumer를 끄면 미발행 복구만 등록하고 요청 큐는 구독하지 않는다")
    void doesNotSubscribeInRelayRole() {
        contextRunner.withPropertyValues("app.conversation.reply-consumer.enabled=false",
            "app.conversation.reply-worker.enabled=false", "app.conversation.reply-relay.enabled=true")
            .run(context -> {
                assertThat(context).hasNotFailed();

                assertThat(context).hasSingleBean(ChatbotReplyRelayScheduler.class);

                assertThat(context).doesNotHaveBean(ChatbotReplyScheduler.class);

                assertThat(context).doesNotHaveBean(AbstractMessageListenerContainer.class);

                then(connectionFactory).shouldHaveNoInteractions();
            });
    }

    @Test
    @DisplayName("Consumer 실행은 설정한 큐와 기존 수동 ACK Listener를 등록하고 유효 마감까지 종료를 기다린다")
    void registersManualConsumerWithShutdownBudget() {
        contextRunner.withPropertyValues("app.conversation.reply-consumer.enabled=true",
            "app.conversation.reply-relay.enabled=true").run(context -> {
                assertThat(context).hasNotFailed();

                assertThat(context).hasSingleBean(AbstractMessageListenerContainer.class);

                AbstractMessageListenerContainer container = context.getBean(AbstractMessageListenerContainer.class);
                assertThat(container.getQueueNames()).containsExactly(QUEUE);

                assertThat(container.getMessageListener()).isSameAs(listener);

                assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);

                assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();

                Long shutdownTimeout = ReflectionTestUtils.invokeMethod(container, "getShutdownTimeout");
                assertThat(shutdownTimeout).isGreaterThanOrEqualTo(Duration.ofSeconds(130).toMillis());

                DefaultLifecycleProcessor processor = context.getBean(DefaultLifecycleProcessor.class);
                Long phaseTimeout = ReflectionTestUtils.invokeMethod(processor, "determineShutdownTimeout",
                    container.getPhase());
                assertThat(phaseTimeout).isGreaterThanOrEqualTo(shutdownTimeout);
            });
    }

    @Test
    @DisplayName("Consumer 역할에서는 배치와 Relay 설정이 켜져 있어도 두 Scheduler를 등록하지 않는다")
    void doesNotRegisterBatchOrRelayInConsumerRole() {
        contextRunner.withPropertyValues("app.conversation.reply-consumer.enabled=true",
            "app.conversation.reply-relay.enabled=true").run(context -> {
                assertThat(context).hasNotFailed();

                assertThat(context).doesNotHaveBean(ChatbotReplyScheduler.class);

                assertThat(context).doesNotHaveBean(ChatbotReplyRelayScheduler.class);
            });
    }

    @Test
    @DisplayName("정상 종료는 신규 수신을 멈춘 뒤 진행 중 AI 응답의 DB 재저장·ACK까지 기다린다")
    void drainsReplyBeforeSignalingShutdown() {
        contextRunner.withPropertyValues("app.conversation.reply-consumer.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context).hasSingleBean(AbstractMessageListenerContainer.class);

            assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch aiStarted = new CountDownLatch(1);
            CountDownLatch finishAi = new CountDownLatch(1);
            ChatbotReplyOutbox outbox = ChatbotReplyOutbox.startNextMessage(
                mock(com.homes.zipsai.conversation.domain.Message.class), 1L, "consumer-trace", "token",
                LocalDateTime.now(), LocalDateTime.now());
            outbox.claim("token", LocalDateTime.now());
            AiConverseRequest request = new AiConverseRequest(null, null, null, null, "consumer-turn",
                "consumer-trace", null, null, null, List.of(), null);
            PendingAiReply pending = new PendingAiReply(EVENT_ID, 0, "token", 1L,
                mock(com.homes.zipsai.conversation.domain.Conversation.class), null, request);
            AiConverseResponse response = new AiConverseResponse(AiConverseResponse.SUCCESS_CODE, request.turnId(),
                new AiConverseResponse.Data(AiRoute.KNOWLEDGE, null, "보일러 점검을 요청해 주세요.", null));
            given(outboxRepository.findById(EVENT_ID)).willReturn(Optional.of(outbox));
            given(worker.claimAndPrepareReply(EVENT_ID)).willReturn(Optional.of(pending));
            given(conversationService.remainingReplyTime(pending)).willReturn(Optional.of(Duration.ofSeconds(20)));
            given(aiConverseClient.converse(eq(request), any())).willAnswer(invocation -> {
                aiStarted.countDown();
                if (!finishAi.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("테스트 AI 응답 대기가 끝나지 않았습니다.");
                }
                return response;
            });
            given(conversationService.saveAiReply(pending, response))
                .willThrow(new DataAccessResourceFailureException("응답 저장 중 일시적 DB 오류"))
                .willAnswer(invocation -> {
                    outbox.complete();
                    return Optional.of(mock(ConversationReplyResponse.class));
                });

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> delivery = executor.submit(() -> {
                    receiver.get().handleDelivery(CONSUMER_TAG, new Envelope(DELIVERY_TAG, false, "", QUEUE),
                        new AMQP.BasicProperties.Builder().contentType("application/json").build(),
                        ("{\"eventId\":" + EVENT_ID + "}").getBytes(StandardCharsets.UTF_8));
                    return null;
                });
                try {
                    assertThat(aiStarted.await(5, TimeUnit.SECONDS)).isTrue();

                    Future<?> closing = executor.submit(context.getSourceApplicationContext()::close);
                    assertThat(canceled.await(5, TimeUnit.SECONDS)).isTrue();

                    assertThat(shutdownSignal.isShuttingDown()).isFalse();

                    assertThat(closing.isDone()).isFalse();

                    finishAi.countDown();
                    delivery.get(10, TimeUnit.SECONDS);
                    closing.get(10, TimeUnit.SECONDS);
                } finally {
                    finishAi.countDown();
                }
            }

            then(aiConverseClient).should().converse(eq(request), any());

            then(conversationService).should(times(2)).saveAiReply(pending, response);

            then(channel).should().basicAck(DELIVERY_TAG, false);

            assertThat(shutdownSignal.isShuttingDown()).isTrue();
        });
    }
}
