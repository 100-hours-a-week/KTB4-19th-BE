package com.homes.zipsai.building.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homes.zipsai.building.domain.RuleDocument;

public interface RuleDocumentRepository extends JpaRepository<RuleDocument, Long> {

    @EntityGraph(attributePaths = "attachment")
    List<RuleDocument> findAllByBuilding_IdAndValidTrueAndDeletedAtIsNullOrderByUpdatedAtDesc(Long buildingId);

    @Query("""
            select d.id from RuleDocument d
            where d.building.id = :buildingId and d.valid = true and d.deletedAt is null
            order by d.updatedAt desc
            """)
    List<Long> findValidIdsByBuildingId(@Param("buildingId") Long buildingId);
}
