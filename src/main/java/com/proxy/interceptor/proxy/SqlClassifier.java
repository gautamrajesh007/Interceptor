package com.proxy.interceptor.proxy;

import com.proxy.interceptor.config.ProxyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SqlClassifier {

    private final ProxyProperties proxyProperties;

    public Classification classify(String sql) {
        if (sql == null || sql.isBlank()) {
            return Classification.ALLOWED;
        }

        String upperSql = sql.toUpperCase();

        // Check critical keywords first (highest priority)
        for (String keyword: proxyProperties.getCriticalKeywords()) {
            if (upperSql.contains(keyword.toUpperCase())) {
                log.debug("SQL classified as CRITICAL (matched: {})", keyword);
                return Classification.CRITICAL;
            }
        }

        // Checked allowed keywords
        for (String keywords : proxyProperties.getAllowedKeywords()) {
            if (upperSql.contains(keywords.toUpperCase())) {
                log.debug("SQL classified as ALLOWED (matched: {})", keywords);
                return Classification.ALLOWED;
            }
        }

        // Default policy
        return proxyProperties.isBlockByDefault() ? Classification.CRITICAL : Classification.ALLOWED;
    }

    public boolean shouldBlock(String sql) {
        return classify(sql) == Classification.CRITICAL;
    }
}
