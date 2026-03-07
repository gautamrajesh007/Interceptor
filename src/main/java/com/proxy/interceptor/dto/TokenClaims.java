package com.proxy.interceptor.dto;

public record TokenClaims(
        String username,
        String role,
        Integer tokenVersion
) {}
