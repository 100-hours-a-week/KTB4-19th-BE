package com.homes.zipsai.conversation.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;

public interface ChatbotReplyOutboxRepository extends JpaRepository<ChatbotReplyOutbox, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select outbox from ChatbotReplyOutbox outbox where outbox.id = :eventId")
    Optional<ChatbotReplyOutbox> findByIdForUpdate(@Param("eventId") Long eventId);
}
