package com.homes.zipsai.conversation.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.conversation.domain.Conversation;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByIdAndDeletedAtIsNull(Long id);

    @Query("""
        select c from Conversation c
        where c.user.id = :userId and c.deletedAt is null
            and (:keyword is null or c.title like concat('%', :keyword, '%'))
        order by c.lastMessageAt desc, c.id desc
        """)
    List<Conversation> findLatestByUserId(@Param("userId") Long userId, @Param("keyword") String keyword, Limit limit);

    @Query("""
        select c from Conversation c
        where c.user.id = :userId and c.deletedAt is null
            and (:keyword is null or c.title like concat('%', :keyword, '%'))
            and (c.lastMessageAt < :cursorAt or (c.lastMessageAt = :cursorAt and c.id < :cursorId))
        order by c.lastMessageAt desc, c.id desc
        """)
    List<Conversation> findLatestByUserIdBefore(@Param("userId") Long userId, @Param("keyword") String keyword,
                                                @Param("cursorAt") LocalDateTime cursorAt,
                                                @Param("cursorId") Long cursorId, Limit limit);

    @Query("select c from Complaint c where c.conversation.id in :conversationIds and c.deletedAt is null")
    List<Complaint> findComplaintsByConversationIds(@Param("conversationIds") Collection<Long> conversationIds);
}
