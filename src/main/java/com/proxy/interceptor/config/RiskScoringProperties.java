package com.proxy.interceptor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "risk-scoring")
@Getter
@Setter
public class RiskScoringProperties {

    private boolean enabled = true;

    // Weights (must sum to 1.0)
    private double syntaxWeight = 0.2;
    private double dataWeight = 0.4;
    private double behaviorWeight = 0.3;
    private double contextWeight = 0.1;

    // Threshold function parameters
    private int minApprovals = 1;
    private int maxApprovals = 5;
    private double gamma = 2.0;

    // Syntax scoring penalties
    private double depthCoefficient = 0.15;
    private double joinCoefficient = 0.2;

    // Data sensitivity mappings: table/column -> sensitivity score
    private Map<String, Double> sensitivityMap = Map.of(
            "users", 0.8,
            "credentials", 1.0,
            "passwords", 1.0,
            "ssn", 1.0,
            "credit_card", 1.0,
            "payment", 0.9,
            "salary", 0.9,
            "accounts", 0.7,
            "orders", 0.3,
            "products", 0.1
    );

    // Business hours (24h format)
    private int businessHourStart = 9;
    private int businessHourEnd = 18;

    // Trusted IP prefixes
    private List<String> trustedIpPrefixes = List.of("10.", "172.16.", "192.168.", "127.");
}