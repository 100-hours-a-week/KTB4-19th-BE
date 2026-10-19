package com.homes.zipsai.building.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homes.zipsai.building.domain.ComplaintDetail;

public interface ComplaintDetailRepository extends JpaRepository<ComplaintDetail, Long> {

    @Query("""
            select cd from ComplaintDetail cd
                join fetch cd.complaint c
                join fetch c.building
                join fetch c.conversation
            where c.status = com.homes.zipsai.building.domain.ComplaintStatus.DONE
                and c.deletedAt is null
                and c.type is not null
                and cd.comment is not null
                and (cd.aiFeedbackSentAt is null or cd.updatedAt > cd.aiFeedbackSentAt)
            order by cd.id
            """)
    Slice<ComplaintDetail> findPendingAiFeedback(Pageable pageable);

    @Query("""
            select new com.homes.zipsai.building.repository.AiFeedbackDeletionTarget(cd.id, c.building.id, cd.updatedAt)
            from ComplaintDetail cd
                join cd.complaint c
            where cd.comment is null
                and cd.aiFeedbackSentAt is not null
            order by cd.id
            """)
    List<AiFeedbackDeletionTarget> findPendingAiFeedbackDeletions(Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query("""
            update ComplaintDetail cd
            set cd.aiFeedbackSentAt = :sentVersion
            where cd.id = :id
                and (cd.aiFeedbackSentAt is null or cd.aiFeedbackSentAt < :sentVersion)
            """)
    int markAiFeedbackSent(@Param("id") Long id, @Param("sentVersion") LocalDateTime sentVersion);

    @Modifying(clearAutomatically = true)
    @Query("""
            update ComplaintDetail cd
            set cd.aiFeedbackSentAt = null
            where cd.id = :id
                and cd.comment is null
                and cd.updatedAt = :updatedAt
            """)
    int markAiFeedbackDeleted(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);

    @Modifying(clearAutomatically = true)
    @Query("""
            update ComplaintDetail cd
            set cd.aiFeedbackSentAt = null
            where cd.id = :id
                and cd.aiFeedbackSentAt = :sentVersion
            """)
    int revertAiFeedbackSent(@Param("id") Long id, @Param("sentVersion") LocalDateTime sentVersion);

    @Modifying(clearAutomatically = true)
    @Query("""
            update ComplaintDetail cd
            set cd.aiFeedbackSentAt = cd.updatedAt
            where cd.id = :id
                and cd.comment is null
                and cd.aiFeedbackSentAt is null
                and cd.updatedAt = :updatedAt
            """)
    int revertAiFeedbackDeleted(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);
}
