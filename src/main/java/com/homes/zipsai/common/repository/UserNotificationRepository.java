package com.homes.zipsai.common.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homes.zipsai.common.domain.UserNotification;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {

    @EntityGraph(attributePaths = "notification")
    @Query("""
        select un from UserNotification un
        where un.user.id = :userId and un.deletedAt is null and un.notification.deletedAt is null
        """)
    Page<UserNotification> findNotificationsByUserId(@Param("userId") Long userId, Pageable pageable);

    @Query("""
        select count(un) from UserNotification un
        where un.user.id = :userId and un.deletedAt is null and un.notification.deletedAt is null
            and un.readAt is null
            and (:excludedUserNotiId is null or un.id <> :excludedUserNotiId)
        """)
    long countUnreadByUserId(@Param("userId") Long userId,
                             @Param("excludedUserNotiId") Long excludedUserNotiId);

    @Query("""
        select un from UserNotification un
        where un.id = :userNotiId and un.user.id = :userId
            and un.deletedAt is null and un.notification.deletedAt is null
        """)
    Optional<UserNotification> findForMarkRead(@Param("userId") Long userId, @Param("userNotiId") Long userNotiId);

    @Modifying(flushAutomatically = true)
    @Query("""
        update UserNotification un set un.readAt = :readAt, un.updatedAt = :readAt
        where un.id = :userNotiId and un.user.id = :userId and un.readAt is null
            and un.deletedAt is null and un.notification.deletedAt is null
        """)
    int markReadIfUnread(@Param("userId") Long userId, @Param("userNotiId") Long userNotiId,
                         @Param("readAt") LocalDateTime readAt);
}
