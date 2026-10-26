package com.homes.zipsai.conversation.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;

public interface ChatbotReplyOutboxRepository extends JpaRepository<ChatbotReplyOutbox, Long> {

    @Query(value = """
        SELECT * FROM Chatbot_reply_outbox
        WHERE event_id > :afterEventId AND event_id <= :lastEventId
            AND deleted_at IS NULL AND (status = 'PENDING'
            OR (status = 'PROCESSING' AND processing_started_at <= :expiredBefore))
        ORDER BY event_id
        LIMIT :limit FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<ChatbotReplyOutbox> findClaimCandidatesForUpdate(@Param("expiredBefore") LocalDateTime expiredBefore,
                                                        @Param("afterEventId") long afterEventId,
                                                        @Param("lastEventId") long lastEventId,
                                                        @Param("limit") int limit);

    @Query("select coalesce(max(outbox.id), 0) from ChatbotReplyOutbox outbox")
    long findLastEventId();

    @Query(value = "SELECT CURRENT_TIMESTAMP(6)", nativeQuery = true)
    LocalDateTime currentTime();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select outbox from ChatbotReplyOutbox outbox where outbox.id = :eventId")
    Optional<ChatbotReplyOutbox> findByIdForUpdate(@Param("eventId") Long eventId);
}
