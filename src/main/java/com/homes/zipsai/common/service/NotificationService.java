package com.homes.zipsai.common.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.common.domain.UserNotification;
import com.homes.zipsai.common.dto.NotificationListResponse;
import com.homes.zipsai.common.dto.NotificationReadResponse;
import com.homes.zipsai.common.dto.NotificationUnreadCountResponse;
import com.homes.zipsai.common.repository.UserNotificationRepository;
import com.homes.zipsai.global.exception.NotFoundException;
import com.homes.zipsai.global.util.TimeUtils;
import com.homes.zipsai.user.service.UserService;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final UserNotificationRepository userNotificationRepository;
    private final ObjectMapper objectMapper;
    private final UserService userService;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public NotificationListResponse getNotifications(Long userId, int page, int size) {
        userService.requireEligibleUser(userId);
        Page<UserNotification> result = userNotificationRepository.findNotificationsByUserId(
            userId, PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return NotificationListResponse.from(result, objectMapper::readTree);
    }

    @Transactional(readOnly = true)
    public NotificationUnreadCountResponse getUnreadCount(Long userId) {
        userService.requireEligibleUser(userId);
        long unreadCount = userNotificationRepository.countUnreadByUserId(userId, null);
        return new NotificationUnreadCountResponse(unreadCount);
    }

    @Transactional
    public NotificationReadResponse markNotificationRead(Long userId, Long userNotiId) {
        userService.requireEligibleUser(userId);
        UserNotification notification = userNotificationRepository.findForMarkRead(userId, userNotiId)
            .orElseThrow(() -> new NotFoundException(NotFoundException.Resource.NOTIFICATION));
        if (notification.getReadAt() == null) {
            int updated = userNotificationRepository.markReadIfUnread(userId, userNotiId, TimeUtils.now());
            if (updated == 0) {
                entityManager.refresh(notification, LockModeType.PESSIMISTIC_READ);
                if (notification.getReadAt() == null) {
                    throw new NotFoundException(NotFoundException.Resource.NOTIFICATION);
                }
            } else {
                entityManager.refresh(notification);
            }
        }
        long unreadCount = userNotificationRepository.countUnreadByUserId(userId, userNotiId);
        return NotificationReadResponse.from(notification, unreadCount);
    }
}
