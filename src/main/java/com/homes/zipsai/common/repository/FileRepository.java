package com.homes.zipsai.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homes.zipsai.common.domain.File;

public interface FileRepository extends JpaRepository<File, Long> {
}
