package com.homes.zipsai.building.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homes.zipsai.building.domain.Complaint;

public interface ComplaintRepository extends JpaRepository<Complaint, Long> {
}
