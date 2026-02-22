package com.proxy.interceptor.controller;

import com.proxy.interceptor.dto.LoginRequest;
import com.proxy.interceptor.dto.LoginResult;
import com.proxy.interceptor.dto.LogoutResult;
import com.proxy.interceptor.security.JwtTokenProvider;
import com.proxy.interceptor.service.AuditService;
import com.proxy.interceptor.service.AuthService;
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
    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                   HttpServletRequest httpServletRequest) {
        LoginResult result = authService.login(request.username(), request.password());

        if (result.success()) {
            auditService.log(request.username(), "login", "Login successful",
                    getClientIp(httpServletRequest));

            return ResponseEntity.ok(Map.of(
                    "token", result.token()
            ));
        }

        auditService.log(request.username(), "login", "Unauthorized access",
                    getClientIp(httpServletRequest));
        return ResponseEntity.status(401).body(Map.of("error", result.error()));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @RequestHeader(value = "Authorization") String authHeader,
            HttpServletRequest request
    ) {
        // Check if Authorization header exists and Bearer is present
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing or invalid token"));
        }

        String token = authHeader.substring(7);

        try {
            // Validate token structure/expiration
            if (!jwtTokenProvider.validateToken(token)) {
                return ResponseEntity.status(401).body(Map.of("error", "Invalid or expired token"));
            }

            String username = jwtTokenProvider.getUsernameFromToken(token);
            Integer jwtVersion = jwtTokenProvider.getTokenVersionFromToken(token);
            if (jwtVersion == null) jwtVersion = 0;

            LogoutResult result = authService.logout(username, jwtVersion);

            if (result.success()) {
                auditService.log(username, "logout", result.message(), getClientIp(request));
                return ResponseEntity.ok().build();
            } else {
                auditService.log(username, "logout", result.message(), getClientIp(request));
                return ResponseEntity.badRequest().body(Map.of("error", result.message()));
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteUser();
    }
}
