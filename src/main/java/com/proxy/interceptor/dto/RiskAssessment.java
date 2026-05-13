package com.proxy.interceptor.dto;

/**
 * Holds the result of a Dynamic Risk Scoring evaluation.
 *
 * @param riskScore         Overall risk score R ∈ [0.0, 1.0]
 * @param requiredApprovals Computed threshold T(R) — number of peer approvals needed
 * @param syntaxScore       S_syn component
 * @param dataScore         S_data component
 * @param behaviorScore     S_beh component
 * @param contextScore      S_ctx component
 */
public record RiskAssessment(
        double riskScore,
        int requiredApprovals,
        double syntaxScore,
        double dataScore,
        double behaviorScore,
        double contextScore
) {}