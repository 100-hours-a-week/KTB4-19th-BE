package com.homes.zipsai.building.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homes.zipsai.building.domain.Building;

public interface BuildingRepository extends JpaRepository<Building, Long> {
    // 매니저가 이미 건물을 등록했는지 전체 엔티티 조회 없이 확인합니다.
    boolean existsByManager_Id(Long managerId);
}
