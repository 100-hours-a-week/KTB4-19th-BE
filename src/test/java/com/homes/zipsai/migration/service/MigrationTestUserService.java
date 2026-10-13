package com.homes.zipsai.migration.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homes.zipsai.user.domain.User;
import com.homes.zipsai.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MigrationTestUserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public Optional<String> findUserName(String email) {
        return userRepository.findByEmail(email).map(User::getUserName);
    }

    @Transactional
    public void changeUserName(Long userId, String userName) {
        userRepository.findById(userId).orElseThrow().changeUserName(userName);
    }

    @Transactional
    public void saveAndFail(User user) {
        userRepository.saveAndFlush(user);
        throw new IllegalStateException("rollback");
    }

    @Transactional
    public void saveAndRenameAfter(User user, Runnable betweenSaveAndRename, String userName) {
        userRepository.saveAndFlush(user);
        betweenSaveAndRename.run();
        user.changeUserName(userName);
    }
}
