package com.homes.zipsai.building.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homes.zipsai.building.domain.InvitationCode;

public interface InvitationCodeRepository extends JpaRepository<InvitationCode, Long> {

    @Query("""
            select c from InvitationCode c
            where c.room.id = :roomId and c.status = com.homes.zipsai.building.domain.InvitationCodeStatus.ACTIVE
                and c.deletedAt is null
            """)
    List<InvitationCode> findActiveByRoomId(@Param("roomId") Long roomId);

    @Query("""
            select c from InvitationCode c
            where c.id = :codeId and c.room.id = :roomId and c.deletedAt is null
            """)
    Optional<InvitationCode> findByIdAndRoomId(
            @Param("codeId") Long codeId,
            @Param("roomId") Long roomId
    );

    @Query("""
            select c from InvitationCode c
            join fetch c.room r
            join fetch r.building b
            where c.code = :code
                and c.deletedAt is null
                and r.deletedAt is null
                and b.deletedAt is null
            """)
    Optional<InvitationCode> findByCodeWithRoomAndBuilding(@Param("code") String code);
}
