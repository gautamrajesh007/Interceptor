package com.proxy.interceptor.controller;

import com.proxy.interceptor.dto.ApiResponse;
import com.proxy.interceptor.model.AuditLog;
import com.proxy.interceptor.service.AuditService;
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
    public ResponseEntity<ApiResponse<List<AuditLog>>> getAuditLogs() {
        return ResponseEntity.ok(ApiResponse.ok(auditService.getRecentLogs()));
    }

    @GetMapping("/user/{username}")
    public ResponseEntity<ApiResponse<List<AuditLog>>> getLogsByUser(
            @PathVariable String username
    ) {
        return ResponseEntity.ok(ApiResponse.ok(auditService.getLogsByUser(username)));
    }
}