package com.homes.zipsai.building.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homes.zipsai.building.domain.Room;

public interface RoomRepository extends JpaRepository<Room, Long> {

    @Query("""
            select case when count(r) > 0 then true else false end
            from Room r
            where r.resident.id = :residentId
                and r.status = com.homes.zipsai.building.domain.RoomStatus.LIVING
                and r.deletedAt is null
            """)
    boolean existsLivingByResidentId(@Param("residentId") Long residentId);

    @Query("""
            select r from Room r
            join fetch r.building b
            join fetch b.manager
            where r.id = :roomId and r.deletedAt is null and b.deletedAt is null
            """)
    Optional<Room> findByIdWithBuildingAndManager(@Param("roomId") Long roomId);
}
