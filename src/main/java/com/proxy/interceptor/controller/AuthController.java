package com.proxy.interceptor.controller;

import com.proxy.interceptor.dto.ApiResponse;
import com.proxy.interceptor.dto.LoginRequest;
import com.proxy.interceptor.dto.LoginResult;
import com.proxy.interceptor.dto.LogoutResult;
import com.proxy.interceptor.service.AuditService;
import com.proxy.interceptor.service.AuthService;
import com.proxy.interceptor.util.RequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuditService auditService;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<?>> login(@Valid @RequestBody LoginRequest request,
                                   HttpServletRequest httpServletRequest) {
        LoginResult result = authService.login(request.username(), request.password());

        if (result.success()) {
            auditService.log(request.username(), "login", "Login successful",
                    RequestUtils.getClientIp(httpServletRequest));

            return ResponseEntity.ok(ApiResponse.ok(Map.of("token", result.token())));
        }

        auditService.log(request.username(), "login", "Unauthorized access",
                    RequestUtils.getClientIp(httpServletRequest));
        return ResponseEntity.status(401).body(ApiResponse.error(result.error()));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<?>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request
    ) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Missing or invalid token"));
        }

        String token = authHeader.substring(7);

        try {
            LogoutResult result = authService.logout(token);

            if (result.success()) {
                auditService.log(result.username(), "logout", result.message(), RequestUtils.getClientIp(request));
                return ResponseEntity.ok(ApiResponse.ok(null));
            } else {
                auditService.log(result.username(), "logout", result.message(), RequestUtils.getClientIp(request));
                return ResponseEntity.badRequest().body(ApiResponse.error(result.message()));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Logout failed"));
        }
    }
}