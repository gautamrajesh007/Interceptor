package com.proxy.interceptor.dto;

import jakarta.validation.constraints.NotBlank;

public record LogoutResult(
        @NotBlank boolean success,
        @NotBlank String message
) {}
