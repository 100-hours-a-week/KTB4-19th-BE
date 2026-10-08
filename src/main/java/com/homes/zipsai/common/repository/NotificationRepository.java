package com.homes.zipsai.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.homes.zipsai.common.domain.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
}
