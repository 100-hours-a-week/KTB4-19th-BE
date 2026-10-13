package com.homes.zipsai.migration.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MigrationTestUserService {

    private final UserRepository userRepository;

    @Transactional
    public void changeUserName(Long userId, String userName) {
        userRepository.findById(userId).orElseThrow().changeUserName(userName);
    }

    @Transactional
    public void saveAndFail(User user) {
        userRepository.saveAndFlush(user);
        throw new IllegalStateException("rollback");
    }
}
