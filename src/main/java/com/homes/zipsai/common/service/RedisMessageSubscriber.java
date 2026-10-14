package com.homes.zipsai.common.service;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.MDC;
import org.springframework.data.redis.connection.Message;
import org.springframework.stereotype.Service;

import com.homes.zipsai.common.event.RedisMessage;
import com.homes.zipsai.global.exception.InvalidPubSubFieldException;
import com.homes.zipsai.global.exception.InvalidPubSubFieldException.Reason;
import com.homes.zipsai.global.logging.StructuredLogger;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.EnumFeature;

@Service
public class RedisMessageSubscriber {

    private final ObjectReader objectReader;
    private final StructuredLogger structuredLogger;

    public RedisMessageSubscriber(ObjectMapper objectMapper, StructuredLogger structuredLogger) {
        this.objectReader = objectMapper.readerFor(new TypeReference<RedisMessage<JsonNode>>() { })
            .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        this.structuredLogger = structuredLogger;
    }

    public <T> void receive(Message message, String channel, Class<T> payloadType, Consumer<T> messageHandler) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        MDC.clear();
        try {
            RedisMessage<JsonNode> redisMessage;
            try {
                redisMessage = objectReader.readValue(message.getBody());
                if (redisMessage == null || redisMessage.payload().isNull()) {
                    throw new InvalidPubSubFieldException("payload", Reason.REQUIRED_VALUE);
                }
            } catch (JacksonException | InvalidPubSubFieldException exception) {
                structuredLogger.pubSubFailed("receive", channel, null, exception);
                return;
            }
            String traceId = redisMessage.traceId();
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString();
            }
            MDC.put("traceId", traceId);
            T payload;
            try {
                payload = objectReader.forType(payloadType).readValue(redisMessage.payload());
            } catch (JacksonException exception) {
                structuredLogger.pubSubFailed("receive", channel, traceId, exception);
                return;
            }
            try {
                messageHandler.accept(payload);
                structuredLogger.pubSubDone("receive", channel, traceId);
            } catch (RuntimeException exception) {
                structuredLogger.pubSubFailed("dispatch", channel, traceId, exception);
            }
        } finally {
            if (previousMdc == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previousMdc);
            }
        }
    }
}
