package com.homes.zipsai.building.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homes.zipsai.building.domain.BuildingCoordinateRetryJob;

public interface BuildingCoordinateRetryJobRepository extends JpaRepository<BuildingCoordinateRetryJob, Long> {
}
