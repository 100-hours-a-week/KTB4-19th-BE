package com.homes.zipsai.building.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homes.zipsai.building.domain.BuildingCoordinateRetryJob;

public interface BuildingCoordinateRetryJobRepository extends JpaRepository<BuildingCoordinateRetryJob, Long> {

    @Query(value = """
            SELECT * FROM Building_coordinate_retry_jobs
            WHERE (status = 'PENDING' AND next_retry_at <= :now)
                OR (status = 'PROCESSING' AND lease_expires_at <= :now)
            ORDER BY building_id
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<BuildingCoordinateRetryJob> findNextForUpdate(@Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from BuildingCoordinateRetryJob j where j.id = :id")
    Optional<BuildingCoordinateRetryJob> findLockedById(@Param("id") Long id);

    @Query(value = "SELECT CURRENT_TIMESTAMP(6)", nativeQuery = true)
    LocalDateTime currentTime();
}
