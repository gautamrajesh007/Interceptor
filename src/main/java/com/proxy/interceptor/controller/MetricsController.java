package com.proxy.interceptor.controller;

import com.proxy.interceptor.service.MetricsService;
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

    @GetMapping
    public ResponseEntity<Map<String, Object>> getMetrics() {
        return ResponseEntity.ok(metricsService.getMetrics());
    }
}
