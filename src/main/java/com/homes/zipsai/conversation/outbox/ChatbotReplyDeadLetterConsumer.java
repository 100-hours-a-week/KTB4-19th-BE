package com.homes.zipsai.conversation.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.homes.zipsai.conversation.config.ChatbotReplyQueueProperties;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutboxStatus;
import com.homes.zipsai.conversation.repository.ChatbotReplyOutboxRepository;
import com.homes.zipsai.conversation.service.ConversationReplyService;

import lombok.RequiredArgsConstructor;

@Component
@ConditionalOnProperty(name = "app.conversation.reply-dead-letter.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ChatbotReplyDeadLetterConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatbotReplyDeadLetterConsumer.class);

    private final ChatbotReplyOutboxRepository outboxRepository;
    private final ConversationReplyService replyService;

    @RabbitListener(
        queues = "${app.conversation.reply-queue.dead-letter-queue:"
            + ChatbotReplyQueueProperties.DEFAULT_DEAD_LETTER_QUEUE + "}",
        concurrency = "${app.conversation.reply-dead-letter.concurrency:1}")
    public void consume(Message message) {
        Long eventId = Long.valueOf(message.getMessageProperties().getMessageId());
        outboxRepository.findById(eventId)
            .filter(outbox -> isUnfinished(outbox))
            .ifPresent(outbox -> fail(outbox));
    }

    private void fail(ChatbotReplyOutbox outbox) {
        LOGGER.warn("DLQ로 온 챗봇 요청을 실패 처리합니다. eventId={}, status={}", outbox.getId(), outbox.getStatus());
        replyService.failReply(outbox.getId(), outbox.getAttemptCount(), outbox.getLockToken());
    }

    private static boolean isUnfinished(ChatbotReplyOutbox outbox) {
        return outbox.getStatus() == ChatbotReplyOutboxStatus.PENDING
            || outbox.getStatus() == ChatbotReplyOutboxStatus.PROCESSING;
    }
}
