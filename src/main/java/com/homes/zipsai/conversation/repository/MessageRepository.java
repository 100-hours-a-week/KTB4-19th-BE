package com.homes.zipsai.conversation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homes.zipsai.conversation.domain.Message;

public interface MessageRepository extends JpaRepository<Message, Long> {

    @Query(value = """
        SELECT message_id FROM Messages
        WHERE conversation_id = :conversationId AND sender_type = 'RESIDENT' AND deleted_at IS NULL
        ORDER BY message_id DESC LIMIT 1 FOR UPDATE
        """, nativeQuery = true)
    Optional<Long> findLatestResidentMessageIdForUpdate(@Param("conversationId") Long conversationId);

    @Query("""
        select m from Message m
        where m.conversation.id = :conversationId and m.deletedAt is null
        order by m.id asc
        """)
    List<Message> findAllByConversationId(@Param("conversationId") Long conversationId);

    @Query("""
        select m from Message m
        where m.conversation.id = :conversationId and m.id < :messageId and m.deletedAt is null
        order by m.id asc
        """)
    List<Message> findAllByConversationIdBefore(@Param("conversationId") Long conversationId,
                                                @Param("messageId") Long messageId);

    @Query("""
        select m from Message m
        where m.conversation.id = :conversationId and m.deletedAt is null
        order by m.id desc
        """)
    List<Message> findLatestByConversationId(@Param("conversationId") Long conversationId, Limit limit);

    @Query("""
        select m from Message m
        where m.conversation.id = :conversationId and m.id < :cursor and m.deletedAt is null
        order by m.id desc
        """)
    List<Message> findLatestByConversationIdBefore(
        @Param("conversationId") Long conversationId, @Param("cursor") Long cursor, Limit limit);
}
