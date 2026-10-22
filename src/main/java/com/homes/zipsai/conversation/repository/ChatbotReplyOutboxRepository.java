package com.homes.zipsai.conversation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homes.zipsai.conversation.domain.ChatbotReplyOutbox;

public interface ChatbotReplyOutboxRepository extends JpaRepository<ChatbotReplyOutbox, Long> {
}
