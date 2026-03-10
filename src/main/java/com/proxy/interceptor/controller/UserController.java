package com.proxy.interceptor.controller;

import com.proxy.interceptor.dto.ApiResponse;
import com.proxy.interceptor.dto.CreateUserRequest;
import com.proxy.interceptor.dto.UserResponse;
import com.proxy.interceptor.model.Role;
import com.proxy.interceptor.model.User;
import com.proxy.interceptor.service.AuditService;
import com.proxy.interceptor.service.AuthService;
import com.proxy.interceptor.service.UserService;
import com.proxy.interceptor.util.RequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Slf4j
public class UserController {

    private final UserService userService;
    private final AuthService authService;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<UserResponse>>> getAllUsers() {
        log.info("Total number of PEERS = {}", userService.getTotalPeerReviewers());
        return ResponseEntity.ok(ApiResponse.ok(userService.getAllUsers()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<UserResponse>> createUser(
            @Valid @RequestBody CreateUserRequest request,
            HttpServletRequest httpRequest
    ) {
        // Exceptions will be caught by GlobalExceptionHandler
        Role role = Role.valueOf(request.role().toUpperCase());
        User user = authService.createUser(request.username(), request.password(), role);

        String adminUsername = (String) httpRequest.getAttribute("username");
        auditService.log(adminUsername, "user_created",
                "Created user: " + request.username() + " with role: " + role,
                RequestUtils.getClientIp(httpRequest));

        return ResponseEntity.ok(ApiResponse.ok(userService.mapToResponse(user)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<?>> deleteUser(
            @PathVariable Long id,
            HttpServletRequest httpRequest
    ) {
        // Exceptions will be caught by GlobalExceptionHandler
        String adminUsername = RequestUtils.getUsername(httpRequest);
        userService.deleteUser(id, adminUsername);

        auditService.log(adminUsername, "user_deleted", "Deleted user #" + id,
                RequestUtils.getClientIp(httpRequest));

        return ResponseEntity.ok(ApiResponse.ok(Map.of("success", true)));
    }
}