package com.proxy.interceptor.service;

import com.proxy.interceptor.dto.UserResponse;
import com.proxy.interceptor.model.User;
import com.proxy.interceptor.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public List<UserResponse> getAllUsers() {
        return userRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public void deleteUser(Long id, String adminUsername) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User does not exist"));

        if (user.getUsername().equals(adminUsername)) {
            throw new IllegalArgumentException("Cannot delete yourself");
        }

        userRepository.delete(user);
    }

    public UserResponse mapToResponse(User user) {
        return new UserResponse(
            user.getId(),
            user.getUsername(),
            user.getRole(),
            user.getCreatedAt(),
            user.getLastLogin()
        );
    }
}
