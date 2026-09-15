package com.homes.zipsai_backend.auth;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s where s.id = :id")
    Optional<RefreshSession> findLockedById(@Param("id") String id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s where s.tokenHash = :hash")
    Optional<RefreshSession> findLockedByHash(@Param("hash") String hash);
}
