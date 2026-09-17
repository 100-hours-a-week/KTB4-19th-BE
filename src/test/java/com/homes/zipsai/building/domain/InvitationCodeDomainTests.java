package com.homes.zipsai.building.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.homes.zipsai.global.exception.ConflictException;
import com.homes.zipsai.user.domain.User;

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
    void roomTransitionsFromEmptyThroughInviteToLivingAndCannotBeReinvited() {
        room.invite();
        assertThat(room.getStatus()).isEqualTo(RoomStatus.INVITED);

        room.invite();
        assertThat(room.getStatus()).isEqualTo(RoomStatus.INVITED);

        room.connect(resident);
        assertThat(room.getStatus()).isEqualTo(RoomStatus.LIVING);
        assertThat(room.getResident()).isSameAs(resident);
        assertConflict(room::invite, ConflictException.Reason.ROOM_OCCUPIED);
    }

    @Test
    void cancellationReturnsInvitedRoomToEmpty() {
        room.invite();
        room.cancelInvitation();

        assertThat(room.getStatus()).isEqualTo(RoomStatus.EMPTY);
        assertThat(room.getResident()).isNull();
    }

    @Test
    void movingOutReturnsLivingRoomToEmptyAndClearsResident() {
        room.invite();
        room.connect(resident);

        room.moveOutResident();

        assertThat(room.getStatus()).isEqualTo(RoomStatus.EMPTY);
        assertThat(room.getResident()).isNull();
    }

    @Test
    void invalidCancellationAndConnectionUseApiContractConflicts() {
        assertConflict(room::cancelInvitation, ConflictException.Reason.INVITATION_CANCEL_NOT_ALLOWED);
        assertConflict(() -> room.connect(resident), ConflictException.Reason.ROOM_CONNECTION_CONFLICT);
        assertConflict(() -> room.connect(null), ConflictException.Reason.ROOM_CONNECTION_CONFLICT);
        assertConflict(room::moveOutResident, ConflictException.Reason.RESIDENT_NOT_FOUND_IN_ROOM);

        room.invite();
        room.connect(resident);
        assertConflict(room::cancelInvitation, ConflictException.Reason.INVITATION_CANCEL_NOT_ALLOWED);
        assertConflict(
                () -> room.connect(new User("another@example.com", "password", "다른 입주민", null)),
                ConflictException.Reason.ROOM_CONNECTION_CONFLICT);
    }

    @Test
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
