package com.homes.zipsai.building.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homes.zipsai.building.domain.BuildingLocation;

public interface BuildingLocationRepository extends JpaRepository<BuildingLocation, Long> {
}
