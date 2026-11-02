package com.homes.zipsai.conversation.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ChatbotReplyPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatbotReplyPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final ChatbotReplyRelayProperties relayProperties;

    public Set<Long> publish(List<ChatbotReplyOutbox> outboxes) {
        List<Sent> sent = new ArrayList<>();
        for (ChatbotReplyOutbox outbox : outboxes) {
            CorrelationData correlation = new CorrelationData(String.valueOf(outbox.getId()));
            try {
                rabbitTemplate.send(relayProperties.exchange(), relayProperties.routingKey(), message(outbox),
                    correlation);
                sent.add(new Sent(outbox.getId(), correlation));
            } catch (AmqpException exception) {
                LOGGER.warn("챗봇 요청을 발행하지 못했습니다. eventId={}", outbox.getId(), exception);
                break;
            }
        }
        return confirmed(sent);
    }

    private Set<Long> confirmed(List<Sent> sent) {
        Set<Long> confirmed = new HashSet<>();
        long deadline = System.nanoTime() + relayProperties.confirmTimeout().toNanos();
        for (Sent message : sent) {
            Duration remaining = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
            if (isConfirmed(message, remaining)) {
                confirmed.add(message.eventId());
            }
        }
        return confirmed;
    }

    private boolean isConfirmed(Sent message, Duration timeout) {
        try {
            CorrelationData.Confirm confirm = message.correlation().getFuture()
                .get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (!confirm.ack()) {
                LOGGER.warn("브로커가 챗봇 요청을 거절했습니다. eventId={}, reason={}", message.eventId(), confirm.reason());
                return false;
            }
            if (message.correlation().getReturned() != null) {
                LOGGER.warn("챗봇 요청이 큐로 라우팅되지 않았습니다. eventId={}, replyText={}", message.eventId(),
                    message.correlation().getReturned().getReplyText());
                return false;
            }
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException exception) {
            LOGGER.warn("챗봇 요청의 발행 확인을 받지 못했습니다. eventId={}", message.eventId(), exception);
            return false;
        }
    }

    private static Message message(ChatbotReplyOutbox outbox) {
        String body = "{\"eventId\":" + outbox.getId() + ",\"attempt\":" + outbox.getAttemptCount() + "}";
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .setContentEncoding(StandardCharsets.UTF_8.name())
            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
            .setMessageId(String.valueOf(outbox.getId()))
            .build();
    }

    private record Sent(Long eventId, CorrelationData correlation) {
    }
}
