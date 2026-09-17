package com.homes.zipsai.building.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.domain.RoomStatus;

public interface RoomRepository extends JpaRepository<Room, Long> {

    @EntityGraph(attributePaths = {"building", "resident"})
    @Query("select r from Room r where r.resident.id = :residentId and r.status = :status and r.deletedAt is null")
    Optional<Room> findByResidentIdAndStatus(@Param("residentId") Long residentId, @Param("status") RoomStatus status);
}
