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
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing or invalid token"));
        }

        String token = authHeader.substring(7);

        try {
            String username = jwtTokenProvider.getUsernameFromToken(token);

            LogoutResult result = authService.logout(username);

            if (result.success()) {
                auditService.log(username, "logout", "User logged out", getClientIp(request));
                return ResponseEntity.ok().build();
            } else {
                auditService.log(username, "logout", "Error logging out", getClientIp(request));
                return ResponseEntity.badRequest().body(Map.of("error", "Error logging out"));
            }
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid or expired token"));
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
