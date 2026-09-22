package com.homes.zipsai.building.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homes.zipsai.building.domain.Building;

public interface BuildingRepository extends JpaRepository<Building, Long> {
    boolean existsByManager_Id(Long managerId);

    Optional<Building> findByManager_IdAndDeletedAtIsNull(Long managerId);
}
