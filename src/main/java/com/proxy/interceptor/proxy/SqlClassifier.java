package com.proxy.interceptor.proxy;

import com.proxy.interceptor.config.ProxyProperties;
import com.proxy.interceptor.proxy.ast.SqlAnalysisResult;
import com.proxy.interceptor.proxy.ast.SqlAnalyzer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SqlClassifier {

    private final ProxyProperties proxyProperties;
    private final SqlAnalyzer sqlAnalyzer;

    public Classification classify(String sql) {
        if (sql == null || sql.isBlank()) {
            return Classification.ALLOWED;
        }

        // 1. Attempt AST Analysis
        SqlAnalysisResult result = sqlAnalyzer.analyze(sql);

        if (result.parseSuccess()) {
            String operation = result.operationType();

            // Check critical keywords based on AST statement type
            for (String keyword : proxyProperties.getCriticalKeywords()) {
                if (operation.equalsIgnoreCase(keyword.trim())) {
                    log.debug("SQL classified as CRITICAL via AST (Operation: {})", keyword);
                    return Classification.CRITICAL;
                }
            }

            // Check allowed keywords based on AST statement type
            for (String keyword : proxyProperties.getAllowedKeywords()) {
                if (operation.equalsIgnoreCase(keyword.trim())) {
                    log.debug("SQL classified as ALLOWED via AST (Operation: {})", keyword);
                    return Classification.ALLOWED;
                }
            }
        } else {
            // 2. Fallback to naive string matching if AST parsing fails (e.g., PostgreSQL-specific syntax)
            return fallbackStringMatch(sql);
        }

        // 3. Default Policy
        return proxyProperties.isBlockByDefault() ? Classification.CRITICAL : Classification.ALLOWED;
    }

    private Classification fallbackStringMatch(String sql) {
        String upperSql = sql.toUpperCase();

        for (String keyword : proxyProperties.getCriticalKeywords()) {
            if (upperSql.contains(keyword.toUpperCase().trim())) {
                log.debug("SQL classified as CRITICAL via fallback matcher (matched: {})", keyword);
                return Classification.CRITICAL;
            }
        }

        for (String keyword : proxyProperties.getAllowedKeywords()) {
            if (upperSql.contains(keyword.toUpperCase().trim())) {
                log.debug("SQL classified as ALLOWED via fallback matcher (matched: {})", keyword);
                return Classification.ALLOWED;
            }
        }

        return proxyProperties.isBlockByDefault() ? Classification.CRITICAL : Classification.ALLOWED;
    }

    public boolean shouldBlock(String sql) {
        return classify(sql) == Classification.CRITICAL;
    }
}