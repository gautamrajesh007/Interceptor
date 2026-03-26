package com.proxy.interceptor.service.risk;

import com.proxy.interceptor.config.RiskScoringProperties;
import com.proxy.interceptor.dto.RiskAssessment;
import com.proxy.interceptor.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Dynamic Risk Scoring (DRS) Service.
 * <p>
 * Orchestrates the four sub-score calculators and computes:
 *   R = min(1.0, w1*S_syn + w2*S_data + w3*S_beh + w4*S_ctx)
 *   T(R) = ceil(T_min + (T_max - T_min) * R^gamma)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DynamicRiskService {

    private final RiskScoringProperties properties;
    private final SyntaxScoreCalculator syntaxCalculator;
    private final DataSensitivityCalculator dataCalculator;
    private final BehaviorScoreCalculator behaviorCalculator;
    private final ContextScoreCalculator contextCalculator;
    private final UserService userService;

    /**
     * Evaluate a SQL query and return its risk assessment.
     *
     * @param sql       The SQL query string
     * @param connId    Connection ID (used for behavioral baseline)
     * @param ipAddress Client IP address (used for context scoring)
     * @return RiskAssessment with overall score and required approvals
     */
    public RiskAssessment evaluateQuery(String sql, String connId, String ipAddress) {
        double syntaxScore = syntaxCalculator.calculate(sql);
        double dataScore = dataCalculator.calculate(sql);
        double behaviorScore = behaviorCalculator.calculate(sql, connId);
        double contextScore = contextCalculator.calculate(ipAddress);

        // R = min(1.0, w1*S_syn + w2*S_data + w3*S_beh + w4*S_ctx)
        double totalRisk = Math.min(1.0,
                (properties.getSyntaxWeight() * syntaxScore) +
                (properties.getDataWeight() * dataScore) +
                (properties.getBehaviorWeight() * behaviorScore) +
                (properties.getContextWeight() * contextScore)
        );

        // Calculate dynamic bounds based on actual registered PEER users
        int totalPeers = userService.getTotalPeerReviewers();
        int requiredApprovals = getRequiredApprovals(totalPeers, totalRisk);

        log.info("DRS evaluation — SQL: {}... | Syntax: {} | Data: {} | Behavior: {} | Context: {} | R={} → T={} (Max Peers: {})",
                sql.substring(0, Math.min(40, sql.length())),
                String.format("%.2f", syntaxScore),
                String.format("%.2f", dataScore),
                String.format("%.2f", behaviorScore),
                String.format("%.2f", contextScore),
                String.format("%.3f", totalRisk),
                requiredApprovals, totalPeers);

        return new RiskAssessment(totalRisk, requiredApprovals, syntaxScore, dataScore, behaviorScore, contextScore);
    }

    private int getRequiredApprovals(int totalPeers, double totalRisk) {
        int dynamicMaxApprovals = Math.min(properties.getMaxApprovals(), totalPeers);

        // Ensure max is never less than min (e.g., if there are 0 peers currently registered)
        dynamicMaxApprovals = Math.max(properties.getMinApprovals(), dynamicMaxApprovals);

        // T(R) = ceil(T_min + (T_max - T_min) * R^gamma)
        int requiredApprovals = (int) Math.ceil(
                properties.getMinApprovals() +
                (dynamicMaxApprovals - properties.getMinApprovals()) *
                Math.pow(totalRisk, properties.getGamma())
        );

        // Clamp to bounds
        requiredApprovals = Math.max(properties.getMinApprovals(),
                Math.min(dynamicMaxApprovals, requiredApprovals));
        return requiredApprovals;
    }
}