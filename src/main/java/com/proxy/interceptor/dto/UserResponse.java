package com.proxy.interceptor.dto;

import com.proxy.interceptor.model.Role;

import java.time.Instant;

public record UserResponse(
        Long id,
        String username,
        Role role,
        Instant createdAt,
        Instant lastLogin
) {}
