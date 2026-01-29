package com.proxy.interceptor.controller;

import com.proxy.interceptor.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final AuditService auditService;

    @Value("${proxy.listen-port}")
    private int proxyPort;

    @Value("${proxy.target-host}")
    private int targetHost;

    @Value("${proxy.target-port}")
    private int targetPort;

    @Value("${proxy.block-by-default}")
    private boolean blockByDefault;

    @Value("${proxy.critical-keywords}")
    private List<String> criticalKeywords;

    @Value("${proxy.allowed-keywords}")
    private List<String> allowedKeywords;

    @Value("${approval.peer-enabled}")
    private boolean peerApprovalEnabled;

    @Value("${approval.min-votes}")
    private int minVotes;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("proxy_port", proxyPort);
        config.put("target_host", targetHost);
        config.put("target_port", targetPort);
        config.put("block_by_default", blockByDefault);
        config.put("critical_keywords", String.join(", ", criticalKeywords));
        config.put("allowed_keywords", String.join(", ", allowedKeywords));
        config.put("peer_approval_enabled", peerApprovalEnabled);
        config.put("peer_approval_min_votes", minVotes);
        return ResponseEntity.ok(config);
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateConfig(
            @RequestBody Map<String, Object> newConfig,
            HttpServletRequest request) {

        // Note: Not implemented. After review, we can use Spring Cloud Config for dynamic configuration.
        // For now, we just audit the attempt.

        String username = (String) request.getAttribute("username");
        auditService.log(username, "config_update_attempted",
            "Configuration update requested (requires restart)", getClientIp(request));

        return ResponseEntity.ok(Map.of(
            "ok", true,
            "message", "Configuration saved.  Restart required to apply changes."
        ));
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
