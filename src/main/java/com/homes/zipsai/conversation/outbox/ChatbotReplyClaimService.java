package com.homes.zipsai.conversation.outbox;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;
import com.homes.zipsai.conversation.domain.ChatbotReplyOutboxStatus;
import com.homes.zipsai.conversation.domain.Conversation;
import com.homes.zipsai.conversation.domain.ConversationStatus;
import com.homes.zipsai.conversation.repository.ChatbotReplyOutboxRepository;
import com.homes.zipsai.conversation.repository.ConversationRepository;
import com.homes.zipsai.conversation.repository.MessageRepository;
import com.homes.zipsai.conversation.service.ConversationReplyLock;
import com.homes.zipsai.conversation.service.ConversationReplyLockProperties;

@Service
@EnableConfigurationProperties(ChatbotReplyWorkerProperties.class)
public class ChatbotReplyClaimService {

    private static final int MAX_CLAIMS = 10;
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatbotReplyClaimService.class);

    private final ChatbotReplyOutboxRepository outboxRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ConversationReplyLock replyLock;
    private final ChatbotReplyWorkerProperties workerProperties;
    private final ConversationReplyLockProperties lockProperties;
    private final TransactionTemplate transactions;

    public ChatbotReplyClaimService(ChatbotReplyOutboxRepository outboxRepository,
                                    ConversationRepository conversationRepository,
                                    MessageRepository messageRepository, ConversationReplyLock replyLock,
                                    ChatbotReplyWorkerProperties workerProperties,
                                    ConversationReplyLockProperties lockProperties,
                                    PlatformTransactionManager transactionManager) {
        this.outboxRepository = outboxRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.replyLock = replyLock;
        this.workerProperties = workerProperties;
        this.lockProperties = lockProperties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public List<Claim> claimBatch() {
        List<Transfer> transfers = new ArrayList<>();
        long lastEventId = transactions.execute(transaction -> outboxRepository.findLastEventId());
        long afterEventId = 0;
        boolean repair = false;
        while (transfers.size() < MAX_CLAIMS && afterEventId < lastEventId) {
            long cursor = afterEventId;
            try {
                afterEventId = transactions.execute(transaction -> {
                    LocalDateTime expiredBefore =
                        outboxRepository.currentTime().minus(workerProperties.attemptTimeout());
                    List<ChatbotReplyOutbox> candidates = outboxRepository.findClaimCandidatesForUpdate(
                        expiredBefore, cursor, lastEventId, MAX_CLAIMS - transfers.size());
                    for (ChatbotReplyOutbox outbox : candidates) {
                        transferOwnership(outbox, transfers);
                    }
                    return candidates.isEmpty() ? lastEventId : candidates.getLast().getId();
                });
            } catch (RuntimeException exception) {
                if (transfers.isEmpty()) {
                    throw exception;
                }
                LOGGER.warn("챗봇 선점의 커밋 결과를 다시 확인합니다. count={}", transfers.size(), exception);
                repair = true;
                break;
            }
        }

        List<Claim> claims = new ArrayList<>();
        for (Transfer transfer : transfers) {
            try {
                boolean recover = repair;
                Optional<Claim> claim = transactions.execute(transaction -> confirmOrRecoverClaim(transfer, recover));
                claim.ifPresent(claims::add);
            } catch (RuntimeException exception) {
                LOGGER.warn("챗봇 선점을 확인하거나 복구하지 못했습니다. eventId={}", transfer.current().eventId(), exception);
            }
        }
        return claims;
    }

    private void transferOwnership(ChatbotReplyOutbox outbox, List<Transfer> transfers) {
        LocalDateTime now = outboxRepository.currentTime();
        if (!isEligible(outbox, now)) {
            return;
        }
        Optional<Conversation> conversation = activeConversation(outbox);
        if (conversation.isEmpty()) {
            return;
        }
        Instant checkedAt = Instant.now();
        now = outboxRepository.currentTime();
        if (!isOpen(outbox, conversation.orElseThrow(), now)) {
            return;
        }
        Duration remaining = Duration.between(now, outbox.requestDeadline(workerProperties.requestTimeout(),
            conversation.orElseThrow()));
        Duration ttl = remaining.compareTo(lockProperties.ttl()) < 0 ? remaining : lockProperties.ttl();
        boolean interrupted = outbox.getStatus() == ChatbotReplyOutboxStatus.PROCESSING;
        Claim previous = new Claim(outbox.getId(), outbox.getAttemptCount(), outbox.getLockToken());
        Claim current = new Claim(outbox.getId(), previous.attempt() + (interrupted ? 1 : 0),
            UUID.randomUUID().toString());
        Transfer transfer = new Transfer(previous, current, conversation.orElseThrow().getId(),
            outbox.getProcessingStartedAt(), now, interrupted);
        transfers.add(transfer);
        if (!replyLock.transfer(transfer.conversationId(), previous.lockToken(), current.lockToken(),
                checkedAt.plus(ttl))) {
            transfers.removeLast();
            return;
        }
        apply(outbox, transfer);
    }

    private Optional<Claim> confirmOrRecoverClaim(Transfer transfer, boolean repair) {
        Optional<ChatbotReplyOutbox> result = outboxRepository.findByIdForUpdate(transfer.current().eventId());
        if (result.isEmpty()) {
            return Optional.empty();
        }
        ChatbotReplyOutbox outbox = result.orElseThrow();
        boolean applied = outbox.isProcessingBy(transfer.current().attempt(), transfer.current().lockToken())
            && outbox.getProcessingStartedAt().equals(transfer.startedAt());
        boolean original = outbox.getLockToken().equals(transfer.previous().lockToken())
            && outbox.getAttemptCount() == transfer.previous().attempt()
            && outbox.getProcessingStartedAt().equals(transfer.previousStartedAt())
            && outbox.getStatus() == (transfer.interrupted()
                ? ChatbotReplyOutboxStatus.PROCESSING : ChatbotReplyOutboxStatus.PENDING);
        if (!applied && (!repair || !original)) {
            return Optional.empty();
        }
        Optional<Conversation> conversation = activeConversation(outbox);
        if (conversation.isEmpty()) {
            return Optional.empty();
        }
        LocalDateTime now = outboxRepository.currentTime();
        if (!isOpen(outbox, conversation.orElseThrow(), now)
                || !now.isBefore(transfer.startedAt().plus(workerProperties.attemptTimeout()))
                || (!applied && !isEligible(outbox, now))
                || !replyLock.isHeldBy(transfer.conversationId(), transfer.current().lockToken())) {
            return Optional.empty();
        }
        if (!applied) {
            apply(outbox, transfer);
        }
        return Optional.of(transfer.current());
    }

    private Optional<Conversation> activeConversation(ChatbotReplyOutbox outbox) {
        return conversationRepository.findByResidentMessageForUpdate(outbox.getMessageId(), outbox.getUserId())
            .filter(conversation -> messageRepository.findLatestResidentMessageIdForUpdate(conversation.getId())
                .filter(outbox.getMessageId()::equals).isPresent());
    }

    private boolean isOpen(ChatbotReplyOutbox outbox, Conversation conversation, LocalDateTime now) {
        return outbox.getDeletedAt() == null && conversation.statusAt(now) == ConversationStatus.ACTIVE
            && now.isBefore(outbox.requestDeadline(workerProperties.requestTimeout(), conversation));
    }

    private boolean isEligible(ChatbotReplyOutbox outbox, LocalDateTime now) {
        return outbox.getDeletedAt() == null && (outbox.getStatus() == ChatbotReplyOutboxStatus.PENDING
            || (outbox.getStatus() == ChatbotReplyOutboxStatus.PROCESSING
                && !now.isBefore(outbox.getProcessingStartedAt().plus(workerProperties.attemptTimeout()))));
    }

    private static void apply(ChatbotReplyOutbox outbox, Transfer transfer) {
        if (transfer.interrupted()) {
            outbox.retry(transfer.current().attempt());
        }
        outbox.claim(transfer.current().lockToken(), transfer.startedAt());
    }

    public record Claim(Long eventId, int attempt, String lockToken) {
    }

    private record Transfer(Claim previous, Claim current, Long conversationId, LocalDateTime previousStartedAt,
                            LocalDateTime startedAt, boolean interrupted) {
    }
}
