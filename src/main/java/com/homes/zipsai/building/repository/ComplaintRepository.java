package com.homes.zipsai.building.repository;

import java.time.LocalDateTime;
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
import com.homes.zipsai.building.dto.response.ManagerComplaintSummaryResponse;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {

    @Query("""
            select new com.homes.zipsai.building.dto.response.ManagerComplaintSummaryResponse(
                count(case when c.status = com.homes.zipsai.building.domain.ComplaintStatus.PENDING then 1 end),
                count(case when c.status = com.homes.zipsai.building.domain.ComplaintStatus.IN_PROGRESS then 1 end),
                count(case when c.status = com.homes.zipsai.building.domain.ComplaintStatus.DONE
                    and c.resolvedAt >= :weekStart
                    and c.resolvedAt <= :now then 1 end),
                count(c.id)
            )
            from Complaint c
            where c.building.id = :buildingId
                and c.deletedAt is null
            """)
    ManagerComplaintSummaryResponse findManagerComplaintSummary(
            @Param("buildingId") Long buildingId,
            @Param("weekStart") LocalDateTime weekStart,
            @Param("now") LocalDateTime now
    );

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

    @EntityGraph(attributePaths = {"building", "attachment"})
    @Query("""
            select c from Complaint c
            where c.user.id = :residentId
                and c.deletedAt is null
                and c.building.deletedAt is null
                and (:keyword is null or lower(c.title) like lower(concat('%', :keyword, '%')))
                and c.status in :statuses
            """)
    Page<Complaint> findResidentComplaints(
            @Param("residentId") Long residentId,
            @Param("keyword") String keyword,
            @Param("statuses") List<ComplaintStatus> statuses,
            Pageable pageable
    );
}
