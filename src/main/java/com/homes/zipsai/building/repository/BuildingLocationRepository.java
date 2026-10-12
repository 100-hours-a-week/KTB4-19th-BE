package com.homes.zipsai.building.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homes.zipsai.building.domain.BuildingLocation;
import com.homes.zipsai.building.dto.NearbyBuilding;

public interface BuildingLocationRepository extends JpaRepository<BuildingLocation, Long> {
    @Query(value = """
            SELECT b.building_id AS buildingId, b.building_name AS buildingName,
                ST_Latitude(l.location) AS latitude, ST_Longitude(l.location) AS longitude
            FROM Building_locations l
            JOIN Buildings b ON b.building_id = l.building_id
            WHERE b.deleted_at IS NULL
                AND ST_Latitude(l.location) BETWEEN :minLatitude AND :maxLatitude
                AND ST_Longitude(l.location) BETWEEN :minLongitude AND :maxLongitude
            ORDER BY b.building_id
            """, nativeQuery = true)
    List<NearbyBuilding> findWithinBounds(
            @Param("minLatitude") double minLatitude,
            @Param("maxLatitude") double maxLatitude,
            @Param("minLongitude") double minLongitude,
            @Param("maxLongitude") double maxLongitude
    );
}
