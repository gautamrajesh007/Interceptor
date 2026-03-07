package com.proxy.interceptor.controller;

import com.proxy.interceptor.dto.CreateUserRequest;
import com.proxy.interceptor.dto.UserResponse;
import com.proxy.interceptor.model.Role;
import com.proxy.interceptor.model.User;
import com.proxy.interceptor.repository.UserRepository;
import com.proxy.interceptor.service.AuditService;
import com.proxy.interceptor.service.AuthService;
import com.proxy.interceptor.service.UserService;
import com.proxy.interceptor.util.RequestUtils;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;
    private final AuthService authService;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<List<UserResponse>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @PostMapping
    public ResponseEntity<?> createUser(
            @Valid @RequestBody CreateUserRequest request,
            HttpServletRequest httpRequest
    ) {
        try {
            Role role = Role.valueOf(request.role().toUpperCase());
            User user = authService.createUser(request.username(), request.password(), role);

            String adminUsername = (String) httpRequest.getAttribute("username");
            auditService.log(adminUsername, "user_created",
                    "Created user: " + request.username() + " with role: " + role,
                    RequestUtils.getClientIp(httpRequest));

            return ResponseEntity.ok(userService.mapToResponse(user));
        } catch (IllegalArgumentException e) {
            if ("Username already exists".equals(e.getMessage())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Try a different username"));
            }
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid role"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id, HttpServletRequest httpRequest) {
        String adminUsername = RequestUtils.getUsername(httpRequest);
        try {
            userService.deleteUser(id, adminUsername);
            auditService.log(adminUsername, "user_deleted", "Deleted user #" + id,
                    RequestUtils.getClientIp(httpRequest));
            return ResponseEntity.ok(Map.of("success", true));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
