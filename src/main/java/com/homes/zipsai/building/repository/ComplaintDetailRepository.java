package com.homes.zipsai.building.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.homes.zipsai.building.domain.ComplaintDetail;

public interface ComplaintDetailRepository extends JpaRepository<ComplaintDetail, Long> {

    @Query("""
            select cd from ComplaintDetail cd
                join fetch cd.complaint c
                join fetch c.building
                join fetch c.conversation
            where c.status = com.homes.zipsai.building.domain.ComplaintStatus.DONE
                and c.deletedAt is null
                and cd.comment is not null
                and cd.aiFeedbackSentAt is null
            order by cd.id
            """)
    Slice<ComplaintDetail> findPendingAiFeedback(Pageable pageable);
}
