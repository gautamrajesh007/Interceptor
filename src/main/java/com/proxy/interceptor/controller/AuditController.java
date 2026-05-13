package com.proxy.interceptor.controller;

import com.proxy.interceptor.dto.ApiResponse;
import com.proxy.interceptor.model.AuditLog;
import com.proxy.interceptor.service.AuditService;
import com.proxy.interceptor.util.RequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<AuditLog>>> getAuditLogs(HttpServletRequest httpRequest) {
        String adminUsername = RequestUtils.getUsername(httpRequest);
        String clientIp = RequestUtils.getClientIp(httpRequest);

        // Audit log for audit log access
        auditService.log(adminUsername, "audit_logs_accessed",
                "Audit logs accessed",
                clientIp);

        return ResponseEntity.ok(ApiResponse.ok(auditService.getRecentLogs()));
    }

    @GetMapping("/user/{username}")
    public ResponseEntity<ApiResponse<List<AuditLog>>> getLogsByUser(
            @PathVariable String username,
            HttpServletRequest httpRequest
    ) {
        String adminUsername = RequestUtils.getUsername(httpRequest);
        String clientIp = RequestUtils.getClientIp(httpRequest);

        // Audit log for user-specific audit log access
        auditService.log(adminUsername, "audit_logs_user_accessed",
                String.format("Audit logs accessed for user: %s", username),
                clientIp);

        return ResponseEntity.ok(ApiResponse.ok(auditService.getLogsByUser(username)));
    }
}