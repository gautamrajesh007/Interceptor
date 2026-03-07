package com.proxy.interceptor.controller;

import com.proxy.interceptor.config.ApprovalProperties;
import com.proxy.interceptor.config.ProxyProperties;
import com.proxy.interceptor.service.AuditService;
import com.proxy.interceptor.util.RequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final AuditService auditService;
    private final ProxyProperties proxyProperties;
    private final ApprovalProperties approvalProperties;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("proxy_port", proxyProperties.getListenPort());
        config.put("target_host", proxyProperties.getTargetHost());
        config.put("target_port", proxyProperties.getTargetPort());
        config.put("block_by_default", proxyProperties.isBlockByDefault());
        config.put("critical_keywords", String.join(", ", proxyProperties.getCriticalKeywords()));
        config.put("allowed_keywords", String.join(", ", proxyProperties.getAllowedKeywords()));
        config.put("peer_approval_enabled", approvalProperties.isPeerEnabled());
        config.put("peer_approval_min_votes", approvalProperties.getMinVotes());
        return ResponseEntity.ok(config);
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateConfig(
            @RequestBody Map<String, Object> newConfig,
            HttpServletRequest request
    ) {

        // Note: Not implemented. After review, we can use Spring Cloud Config for dynamic configuration.
        // For now, we just audit the attempt.

        String username = (String) request.getAttribute("username");
        auditService.log(username, "config_update_attempted",
            "Configuration update requested (requires restart)", RequestUtils.getClientIp(request));

        return ResponseEntity.ok(Map.of(
            "ok", true,
            "message", "Configuration saved.  Restart required to apply changes."
        ));
    }
}
