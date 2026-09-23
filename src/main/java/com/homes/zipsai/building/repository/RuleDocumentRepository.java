package com.homes.zipsai.building.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homes.zipsai.building.domain.RuleDocument;

public interface RuleDocumentRepository extends JpaRepository<RuleDocument, Long> {
    List<RuleDocument> findAllByBuilding_IdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long buildingId);
}
