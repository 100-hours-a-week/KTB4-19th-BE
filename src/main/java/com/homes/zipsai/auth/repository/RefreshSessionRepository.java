package com.homes.zipsai.auth.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homes.zipsai.auth.domain.RefreshSession;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s where s.id = :id")
    Optional<RefreshSession> findLockedById(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s where s.tokenHash = :hash")
    Optional<RefreshSession> findLockedByHash(@Param("hash") String hash);
}
