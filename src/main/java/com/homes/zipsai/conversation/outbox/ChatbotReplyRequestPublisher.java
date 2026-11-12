package com.homes.zipsai.conversation.outbox;

import java.nio.charset.StandardCharsets;
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

import com.homes.zipsai.conversation.ai.AiConverseRequest;
import com.homes.zipsai.conversation.service.PendingAiReply;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class ChatbotReplyRequestPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatbotReplyRequestPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final ChatbotReplyRelayProperties relayProperties;
    private final ObjectMapper objectMapper;

    public boolean publish(PendingAiReply pendingReply) {
        Long eventId = pendingReply.eventId();
        CorrelationData correlation = new CorrelationData(String.valueOf(eventId));
        try {
            rabbitTemplate.send(relayProperties.exchange(), relayProperties.routingKey(), message(pendingReply),
                correlation);
        } catch (AmqpException exception) {
            LOGGER.warn("챗봇 요청을 발행하지 못했습니다. eventId={}", eventId, exception);
            return false;
        }
        return isConfirmed(eventId, correlation);
    }

    private Message message(PendingAiReply pendingReply) {
        byte[] body = objectMapper.writeValueAsBytes(
            new ChatbotReplyRequestMessage(pendingReply.eventId(), pendingReply.aiRequest()));
        return MessageBuilder.withBody(body)
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .setContentEncoding(StandardCharsets.UTF_8.name())
            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
            .setMessageId(String.valueOf(pendingReply.eventId()))
            .build();
    }

    private boolean isConfirmed(Long eventId, CorrelationData correlation) {
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                .get(relayProperties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.ack()) {
                LOGGER.warn("브로커가 챗봇 요청을 거절했습니다. eventId={}, reason={}", eventId, confirm.reason());
                return false;
            }
            if (correlation.getReturned() != null) {
                LOGGER.warn("챗봇 요청이 큐로 라우팅되지 않았습니다. eventId={}, replyText={}", eventId,
                    correlation.getReturned().getReplyText());
                return false;
            }
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException exception) {
            LOGGER.warn("챗봇 요청의 발행 확인을 받지 못했습니다. eventId={}", eventId, exception);
            return false;
        }
    }

    record ChatbotReplyRequestMessage(Long eventId, AiConverseRequest request) {
    }
}
