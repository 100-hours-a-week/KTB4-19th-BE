package com.homes.zipsai.building.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.domain.RoomStatus;

public interface RoomRepository extends JpaRepository<Room, Long> {

    @EntityGraph(attributePaths = "resident")
    List<Room> findAllByBuilding_IdAndDeletedAtIsNull(Long buildingId);

    long countByBuilding_IdAndDeletedAtIsNull(Long buildingId);

    long countByBuilding_IdAndStatusAndDeletedAtIsNull(Long buildingId, RoomStatus status);

    // 요청한 번호 중 건물에 이미 등록된 호실이 하나라도 있는지 확인합니다.
    boolean existsByBuilding_IdAndRoomNoIn(Long buildingId, Collection<String> roomNos);

    @EntityGraph(attributePaths = {"building", "resident"})
    @Query("select r from Room r where r.resident.id = :residentId and r.status = :status and r.deletedAt is null")
    Optional<Room> findByResidentIdAndStatus(@Param("residentId") Long residentId, @Param("status") RoomStatus status);

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
