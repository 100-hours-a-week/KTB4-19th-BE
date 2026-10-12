package com.homes.zipsai.building.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.domain.Room;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.common.event.UserAccessRevokedEvent;
import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.global.exception.ForbiddenException;
import com.homes.zipsai.user.domain.User;

@ExtendWith(MockitoExtension.class)
class RoomServiceMoveOutTest {

    private static final long MANAGER_ID = 9L;
    private static final long OTHER_MANAGER_ID = 8L;
    private static final long RESIDENT_ID = 1L;
    private static final long ROOM_ID = 30L;

    @Mock
    BuildingRepository buildingRepository;

    @Mock
    RoomRepository roomRepository;

    @Mock
    ApplicationEventPublisher eventPublisher;

    RoomService roomService;

    @BeforeEach
    void setUp() {
        roomService = new RoomService(buildingRepository, roomRepository, eventPublisher);
    }

    @Test
    @DisplayName("입주민을 퇴거하면 그 입주민의 접근 회수 이벤트를 발행한다")
    void publishesAccessRevokedEventWhenResidentMovesOut() {
        given(roomRepository.findByIdWithBuildingAndManager(ROOM_ID)).willReturn(Optional.of(livingRoom()));

        roomService.moveOutResident(MANAGER_ID, ROOM_ID);

        then(eventPublisher).should().publishEvent(new UserAccessRevokedEvent(RESIDENT_ID));
    }

    @Test
    @DisplayName("다른 관리자의 호실은 퇴거하지 않고 이벤트도 발행하지 않는다")
    void doesNotPublishEventForAnotherManagersRoom() {
        given(roomRepository.findByIdWithBuildingAndManager(ROOM_ID)).willReturn(Optional.of(livingRoom()));

        assertThatThrownBy(() -> roomService.moveOutResident(OTHER_MANAGER_ID, ROOM_ID))
            .isInstanceOf(ForbiddenException.class);

        then(eventPublisher).should(never()).publishEvent(any());
    }

    @Test
    @DisplayName("입주민이 없는 호실은 퇴거하지 않고 이벤트도 발행하지 않는다")
    void doesNotPublishEventForEmptyRoom() {
        given(roomRepository.findByIdWithBuildingAndManager(ROOM_ID)).willReturn(Optional.of(emptyRoom()));

        assertThatThrownBy(() -> roomService.moveOutResident(MANAGER_ID, ROOM_ID))
            .isInstanceOf(ConflictException.class);

        then(eventPublisher).should(never()).publishEvent(any());
    }

    private static Room livingRoom() {
        User resident = user(RESIDENT_ID);
        Room room = emptyRoom();
        room.invite();
        room.moveIn(resident);
        return room;
    }

    private static Room emptyRoom() {
        Building building = new Building(user(MANAGER_ID), "서울시 테스트로 1", "테스트빌");
        Room room = new Room(building, "302");
        ReflectionTestUtils.setField(room, "id", ROOM_ID);
        return room;
    }

    private static User user(long id) {
        User user = new User(id + "@example.com", "password", "테스트", null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
