package com.proxy.interceptor.service.risk;

import com.proxy.interceptor.config.RiskScoringProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Calculates the Context Score S_ctx ∈ [0, 1].
 * <p>
 * Formula: S_ctx = max(P_time, P_geo, P_ip)
 * <p>
 * - P_time: 0.0 during business hours, scales up to 0.8 at 3 AM
 * - P_ip:   0.0 for trusted/internal IPs, 0.5-1.0 for unknown external IPs
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContextScoreCalculator {

    private final RiskScoringProperties properties;

    public double calculate(String ipAddress) {
        double timePenalty = calculateTimePenalty();
        double ipPenalty = calculateIpPenalty(ipAddress);

        return Math.min(1.0, Math.max(timePenalty, ipPenalty));
    }

    /**
     * P_time: Penalty based on time of day.
     * 0.0 during business hours, up to 0.8 during deep night hours.
     */
    private double calculateTimePenalty() {
        LocalTime now = LocalTime.now(ZoneId.systemDefault());
        int hour = now.getHour();

        int start = properties.getBusinessHourStart();
        int end = properties.getBusinessHourEnd();

        if (hour >= start && hour < end) {
            return 0.0; // Business hours — no penalty
        }

        // Calculate distance from nearest business hour boundary
        int distFromStart = Math.min(
                Math.abs(hour - start),
                24 - Math.abs(hour - start)
        );
        int distFromEnd = Math.min(
                Math.abs(hour - end),
                24 - Math.abs(hour - end)
        );
        int minDist = Math.min(distFromStart, distFromEnd);

        // Scale: 1 hour outside → 0.1, max at ~8 hours outside → 0.8
        return Math.min(0.8, minDist * 0.1);
    }

    /**
     * P_ip: Penalty based on IP address.
     * 0.0 for localhost and private/trusted networks.
     * 0.5 for unknown external IPs.
     * 1.0 if IP is null/empty (anomalous).
     */
    private double calculateIpPenalty(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return 1.0; // No IP is highly suspicious
        }

        // Localhost
        if (ipAddress.equals("127.0.0.1") || ipAddress.equals("0:0:0:0:0:0:0:1") || ipAddress.equals("::1")) {
            return 0.0;
        }

        // Check trusted IP prefixes
        for (String prefix : properties.getTrustedIpPrefixes()) {
            if (ipAddress.startsWith(prefix)) {
                return 0.0;
            }
        }

        // External / unknown IP
        return 0.5;
    }
}