package com.homes.zipsai.building.repository;

import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homes.zipsai.building.domain.Room;

public interface RoomRepository extends JpaRepository<Room, Long> {
    // 요청한 번호 중 건물에 이미 등록된 호실이 하나라도 있는지 확인합니다.
    boolean existsByBuilding_IdAndRoomNoIn(Long buildingId, Collection<String> roomNos);
}
