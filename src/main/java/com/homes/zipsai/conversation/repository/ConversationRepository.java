package com.homes.zipsai.conversation.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homes.zipsai.conversation.domain.Conversation;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByIdAndDeletedAtIsNull(Long id);
}
