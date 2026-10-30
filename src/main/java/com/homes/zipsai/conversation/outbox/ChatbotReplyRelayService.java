package com.homes.zipsai.conversation.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;
import com.homes.zipsai.conversation.repository.ChatbotReplyOutboxRepository;

@Service
@EnableConfigurationProperties(ChatbotReplyRelayProperties.class)
public class ChatbotReplyRelayService {

    private final ChatbotReplyOutboxRepository outboxRepository;
    private final ChatbotReplyPublisher publisher;
    private final ChatbotReplyRelayProperties relayProperties;
    private final TransactionTemplate transactions;

    public ChatbotReplyRelayService(ChatbotReplyOutboxRepository outboxRepository, ChatbotReplyPublisher publisher,
                                    ChatbotReplyRelayProperties relayProperties,
                                    PlatformTransactionManager transactionManager) {
        this.outboxRepository = outboxRepository;
        this.publisher = publisher;
        this.relayProperties = relayProperties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public int relayBatch() {
        return transactions.execute(transaction -> {
            LocalDateTime now = outboxRepository.currentTime();
            List<ChatbotReplyOutbox> candidates = outboxRepository.findRelayCandidatesForUpdate(
                now.minus(relayProperties.republishDelay()), relayProperties.batchSize());
            if (candidates.isEmpty()) {
                return 0;
            }
            Set<Long> confirmed = publisher.publish(candidates);
            LocalDateTime publishedAt = outboxRepository.currentTime();
            int marked = 0;
            for (ChatbotReplyOutbox outbox : candidates) {
                if (confirmed.contains(outbox.getId())
                        && outbox.markPublished(outbox.getAttemptCount(), publishedAt)) {
                    marked++;
                }
            }
            return marked;
        });
    }
}
