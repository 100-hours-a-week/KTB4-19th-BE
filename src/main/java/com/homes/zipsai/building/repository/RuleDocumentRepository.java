package com.homes.zipsai.building.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homes.zipsai.building.domain.RuleDocument;
import com.homes.zipsai.building.domain.RuleDocumentIndexingStatus;

public interface RuleDocumentRepository extends JpaRepository<RuleDocument, Long> {

    @EntityGraph(attributePaths = "attachment")
    List<RuleDocument> findAllByBuilding_IdAndValidTrueAndDeletedAtIsNullOrderByUpdatedAtDesc(Long buildingId);

    @Query("""
            select d.id from RuleDocument d
            where d.building.id = :buildingId and d.valid = true and d.deletedAt is null
            order by d.updatedAt desc
            """)
    List<Long> findValidIdsByBuildingId(@Param("buildingId") Long buildingId);

    @EntityGraph(attributePaths = "attachment")
    @Query("select d from RuleDocument d where d.id = :id")
    Optional<RuleDocument> findWithAttachmentById(@Param("id") Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RuleDocument d
            set d.indexingStatus = :status, d.indexingError = :error
            where d.id = :id and d.version = :version
            """)
    int updateIndexingResult(@Param("id") Long id, @Param("version") int version,
                             @Param("status") RuleDocumentIndexingStatus status, @Param("error") String error);
}
