package com.homes.zipsai.building.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.homes.zipsai.building.domain.Complaint;
import com.homes.zipsai.building.domain.ComplaintStatus;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {

    @EntityGraph(attributePaths = {"building", "conversation", "attachment"})
    Optional<Complaint> findByIdAndDeletedAtIsNull(Long complaintId);

    @EntityGraph(attributePaths = "building")
    @Query("""
            select c from Complaint c
            where c.building.id = :buildingId
                and c.deletedAt is null
                and c.building.deletedAt is null
                and (:keyword is null or lower(c.title) like lower(concat('%', :keyword, '%')))
                and c.status in :statuses
                and (:urgentOnly = false or c.urgency >= :urgentThreshold)
            """)
    Page<Complaint> findManagerComplaints(
            @Param("buildingId") Long buildingId,
            @Param("keyword") String keyword,
            @Param("statuses") List<ComplaintStatus> statuses,
            @Param("urgentOnly") boolean urgentOnly,
            @Param("urgentThreshold") int urgentThreshold,
            Pageable pageable
    );
}
