package com.homes.zipsai.conversation.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.homes.zipsai.global.exception.ConflictException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(ConversationReplyLockProperties.class)
public class ConversationReplyLock {

    static final String KEY_PREFIX = "conversation:replying:";

    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of("""
        if redis.call('get', KEYS[1]) == ARGV[1] then
            return redis.call('del', KEYS[1])
        end
        return 0
        """, Long.class);
    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationReplyLock.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ConversationReplyLockProperties properties;

    public String acquire(Long conversationId) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(key(conversationId), token, properties.ttl());
        if (!Boolean.TRUE.equals(acquired)) {
            throw new ConflictException(ConflictException.Reason.CONVERSATION_BUSY);
        }
        return token;
    }

    public void release(Long conversationId, String token) {
        try {
            stringRedisTemplate.execute(RELEASE_SCRIPT, List.of(key(conversationId)), token);
        } catch (DataAccessException exception) {
            LOGGER.warn("대화방 답변 잠금을 풀지 못해 TTL로 풀립니다. conversationId={}", conversationId, exception);
        }
    }

    private static String key(Long conversationId) {
        return KEY_PREFIX + conversationId;
    }
}
