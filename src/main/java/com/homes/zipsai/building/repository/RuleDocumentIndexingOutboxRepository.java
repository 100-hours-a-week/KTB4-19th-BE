package com.homes.zipsai.building.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homes.zipsai.building.domain.RuleDocumentIndexingOutbox;
import com.homes.zipsai.building.domain.RuleDocumentIndexingOutboxStatus;

public interface RuleDocumentIndexingOutboxRepository extends JpaRepository<RuleDocumentIndexingOutbox, Long> {

    @Query("""
            select o.id from RuleDocumentIndexingOutbox o
            where o.status = com.homes.zipsai.building.domain.RuleDocumentIndexingOutboxStatus.PENDING
                and o.nextRetryAt <= :now
                and o.deletedAt is null
            order by o.id
            """)
    List<Long> findDueIds(@Param("now") LocalDateTime now, Pageable pageable);

    long countByStatus(RuleDocumentIndexingOutboxStatus status);
}
