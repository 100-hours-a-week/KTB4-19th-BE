package com.homes.zipsai.building.repository;

import java.time.LocalDateTime;

public record AiFeedbackDeletionTarget(Long complaintDetailId, Long buildingId, LocalDateTime updatedAt) {
}
