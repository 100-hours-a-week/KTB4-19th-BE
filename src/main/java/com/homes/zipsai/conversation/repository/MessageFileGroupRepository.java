package com.homes.zipsai.conversation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homes.zipsai.conversation.domain.MessageFileGroup;

public interface MessageFileGroupRepository extends JpaRepository<MessageFileGroup, Long> {

    @Query("""
        select g from MessageFileGroup g
        join fetch g.attachment
        where g.message.id in :messageIds and g.deletedAt is null
        order by g.message.id asc, g.fileGroupSeq asc
        """)
    List<MessageFileGroup> findAllByMessageIds(@Param("messageIds") List<Long> messageIds);

    @Modifying
    @Query("delete from MessageFileGroup g where g.message.id = :messageId")
    void deleteAllByMessageId(@Param("messageId") Long messageId);
}
