package com.homes.zipsai.building.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homes.zipsai.building.domain.ComplaintDetail;

public interface ComplaintDetailRepository extends JpaRepository<ComplaintDetail, Long> {
}
