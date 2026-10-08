package com.homes.zipsai.common.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.building.domain.Building;
import com.homes.zipsai.building.repository.BuildingRepository;
import com.homes.zipsai.building.repository.RoomRepository;
import com.homes.zipsai.common.domain.ComplaintNotificationContent;
import com.homes.zipsai.common.domain.Notification;
import com.homes.zipsai.common.domain.UserNotification;
import com.homes.zipsai.common.event.ComplaintNotificationEvent;
import com.homes.zipsai.common.repository.NotificationRepository;
import com.homes.zipsai.common.repository.UserNotificationRepository;
import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.domain.UserRole;
import com.homes.zipsai.user.domain.UserStatus;
import com.homes.zipsai.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class ComplaintNotificationStorageService {

    private final NotificationRepository notificationRepository;
    private final UserNotificationRepository userNotificationRepository;
    private final BuildingRepository buildingRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserNotification store(ComplaintNotificationEvent event) {
        ComplaintNotificationContent content = event.content();
        User recipient;
        if (content.type() == ComplaintNotificationContent.Type.COMPLAINT_CREATED) {
            Building building = buildingRepository.findById(content.buildingId()).orElse(null);
            if (building == null || building.getDeletedAt() != null) {
                return null;
            }
            recipient = building.getManager();
            if (!isEligible(recipient, UserRole.MANAGER)) {
                return null;
            }
        } else {
            recipient = userRepository.findById(event.residentId()).orElse(null);
            if (!isEligible(recipient, UserRole.RESIDENT)
                    || !roomRepository.existsLivingByResidentId(event.residentId())) {
                return null;
            }
        }
        Notification notification = notificationRepository.save(new Notification(objectMapper.writeValueAsString(content)));
        return userNotificationRepository.save(new UserNotification(recipient, notification));
    }

    private boolean isEligible(User user, UserRole role) {
        return user != null && user.getDeletedAt() == null && user.getStatus() == UserStatus.ACTIVE
            && user.getRole() == role;
    }
}
