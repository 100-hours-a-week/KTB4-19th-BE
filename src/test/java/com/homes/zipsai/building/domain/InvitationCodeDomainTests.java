package com.homes.zipsai.building.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.user.domain.User;

@DisplayName("초대코드·호실 상태 도메인")
class InvitationCodeDomainTests {
    private LocalDateTime issuedAt;
    private User manager;
    private User resident;
    private Room room;

    @BeforeEach
    void setUp() {
        issuedAt = LocalDateTime.of(2026, 9, 16, 10, 0);
        manager = new User("manager@example.com", "password", "관리자", null);
        resident = new User("resident@example.com", "password", "입주민", null);
        room = new Room(new Building(manager, "서울시", "A타워"), "101호");
    }

    @Test
    @DisplayName("만료 시각을 포함해 코드를 만료 판정하고 초대 호실 상태는 유지한다")
    void expiresAtIsInclusiveAndKeepsInvitedRoomStatus() {
        room.invite();
        InvitationCode code = new InvitationCode(room, "ABC234", issuedAt.plusHours(3));

        assertThat(code.isExpired(issuedAt.plusHours(2).plusMinutes(59).plusSeconds(59))).isFalse();
        assertThat(code.getStatus()).isEqualTo(InvitationCodeStatus.ACTIVE);
        assertThat(room.getStatus()).isEqualTo(RoomStatus.INVITED);

        assertThat(code.isExpired(issuedAt.plusHours(3))).isTrue();
        assertThat(code.getStatus()).isEqualTo(InvitationCodeStatus.ACTIVE);
        assertThat(room.getStatus()).isEqualTo(RoomStatus.INVITED);
        assertThat(code.isExpired(issuedAt.plusHours(3).plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("만료된 코드와 사용된 코드는 만료 판정으로 상태가 바뀌지 않는다")
    void expiryKeepsTerminalCodeStatesUnchanged() {
        InvitationCode expired = code("ABC234");

        assertThat(expired.isExpired(issuedAt.plusHours(3))).isTrue();
        assertThat(expired.getStatus()).isEqualTo(InvitationCodeStatus.ACTIVE);
        assertThat(expired.expireIfExpired(issuedAt.plusHours(3))).isTrue();
        assertThat(expired.getStatus()).isEqualTo(InvitationCodeStatus.EXPIRED);
        assertThat(expired.expireIfExpired(issuedAt.plusHours(4))).isFalse();
        expired.expire();
        assertThat(expired.getStatus()).isEqualTo(InvitationCodeStatus.EXPIRED);

        InvitationCode used = code("DEF567");
        used.use();
        assertThat(used.isExpired(issuedAt.plusHours(4))).isFalse();
        assertThat(used.getStatus()).isEqualTo(InvitationCodeStatus.USED);
        used.expire();
        assertThat(used.getStatus()).isEqualTo(InvitationCodeStatus.USED);
    }

    @Test
    @DisplayName("빈 호실은 초대 후 입주 상태가 되고 입주 중 재초대는 거부한다")
    void roomTransitionsFromEmptyThroughInviteToLivingAndCannotBeReinvited() {
        room.invite();
        assertThat(room.getStatus()).isEqualTo(RoomStatus.INVITED);

        room.invite();
        assertThat(room.getStatus()).isEqualTo(RoomStatus.INVITED);

        room.moveIn(resident);
        assertThat(room.getStatus()).isEqualTo(RoomStatus.LIVING);
        assertThat(room.getResident()).isSameAs(resident);
        assertConflict(room::invite, ConflictException.Reason.ROOM_OCCUPIED);
    }

    @Test
    @DisplayName("초대를 취소하면 호실은 공실로 돌아간다")
    void cancellationReturnsInvitedRoomToEmpty() {
        room.invite();
        room.cancelInvitation();

        assertThat(room.getStatus()).isEqualTo(RoomStatus.EMPTY);
        assertThat(room.getResident()).isNull();
    }

    @Test
    @DisplayName("퇴거 처리 시 호실은 공실이 되고 입주민 연결을 해제한다")
    void movingOutReturnsLivingRoomToEmptyAndClearsResident() {
        room.invite();
        room.moveIn(resident);

        room.moveOutResident();

        assertThat(room.getStatus()).isEqualTo(RoomStatus.EMPTY);
        assertThat(room.getResident()).isNull();
    }

    @Test
    @DisplayName("허용되지 않은 취소와 연결은 API 계약의 충돌 사유를 반환한다")
    void invalidCancellationAndConnectionUseApiContractConflicts() {
        assertConflict(room::cancelInvitation, ConflictException.Reason.INVITATION_CANCEL_NOT_ALLOWED);
        assertConflict(() -> room.moveIn(resident), ConflictException.Reason.ROOM_CONNECTION_CONFLICT);
        assertConflict(() -> room.moveIn(null), ConflictException.Reason.ROOM_CONNECTION_CONFLICT);
        assertConflict(room::moveOutResident, ConflictException.Reason.RESIDENT_NOT_FOUND_IN_ROOM);

        room.invite();
        room.moveIn(resident);
        assertConflict(room::cancelInvitation, ConflictException.Reason.INVITATION_CANCEL_NOT_ALLOWED);
        assertConflict(
                () -> room.moveIn(new User("another@example.com", "password", "다른 입주민", null)),
                ConflictException.Reason.ROOM_CONNECTION_CONFLICT);
    }

    @Test
    @DisplayName("사용·만료 코드 재사용은 API 계약의 충돌 사유를 반환한다")
    void codeUseConflictsMatchUsedAndExpiredApiResponses() {
        InvitationCode used = code("ABC234");
        used.use();
        assertThat(used.getStatus()).isEqualTo(InvitationCodeStatus.USED);
        assertConflict(used::use, ConflictException.Reason.INVITATION_CODE_ALREADY_USED);

        InvitationCode expired = code("DEF567");
        expired.expire();
        assertThat(expired.getStatus()).isEqualTo(InvitationCodeStatus.EXPIRED);
        expired.expire();
        assertThat(expired.getStatus()).isEqualTo(InvitationCodeStatus.EXPIRED);
        assertConflict(expired::use, ConflictException.Reason.INVITATION_CODE_EXPIRED);
    }

    private InvitationCode code(String value) {
        return new InvitationCode(room, value, issuedAt.plusHours(3));
    }

    private void assertConflict(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            ConflictException.Reason reason
    ) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(ConflictException.class, exception -> {
                    assertThat(exception.status).isEqualTo(409);
                    assertThat(exception.code).isEqualTo(reason.name());
                    assertThat(exception.details.get("field")).isEqualTo(
                            reason == ConflictException.Reason.ROOM_OCCUPIED
                                    || reason == ConflictException.Reason.INVITATION_CANCEL_NOT_ALLOWED
                                    || reason == ConflictException.Reason.RESIDENT_NOT_FOUND_IN_ROOM
                                    ? "roomId" : "code");
                });
    }
}
