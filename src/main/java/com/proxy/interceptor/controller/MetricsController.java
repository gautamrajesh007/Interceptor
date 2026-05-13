package com.proxy.interceptor.controller;

import com.proxy.interceptor.dto.ApiResponse;
import com.proxy.interceptor.service.AuditService;
import com.proxy.interceptor.service.MetricsService;
import com.proxy.interceptor.util.RequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final MetricsService metricsService;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMetrics(HttpServletRequest httpRequest) {
        String username = RequestUtils.getUsername(httpRequest);
        String clientIp = RequestUtils.getClientIp(httpRequest);

        // Audit log for metrics access
        auditService.log(username, "metrics_accessed",
                "Metrics accessed",
                clientIp);

        return ResponseEntity.ok(ApiResponse.ok(metricsService.getMetrics()));
    }
}