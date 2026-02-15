package com.proxy.interceptor.proxy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class SqlClassifier {

    @Value("${proxy.critical-keywords}")
    private List<String> criticalKeywords;

    @Value("${proxy.allowed-keywords}")
    private List<String> allowedKeywords;

    @Value("${proxy.block-by-default}")
    private boolean blockedByDefault;

    public Classification classify(String sql) {
        if (sql == null || sql.isBlank()) {
            return Classification.ALLOWED;
        }

        String upperSql = sql.toUpperCase();

        // Check critical keywords first (highest priority)
        for (String keyword: criticalKeywords) {
            if (upperSql.contains(keyword.toUpperCase())) {
                log.debug("SQL classified as CRITICAL (matched: {})", keyword);
                return Classification.CRITICAL;
            }
        }

        // Checked allowed keywords
        for (String keywords : allowedKeywords) {
            if (upperSql.contains(keywords.toUpperCase())) {
                log.debug("SQL classified as ALLOWED (matched: {})", keywords);
                return Classification.ALLOWED;
            }
        }

        // Default policy
        return blockedByDefault ? Classification.CRITICAL : Classification.ALLOWED;
    }

    public boolean shouldBlock(String sql) {
        return classify(sql) == Classification.CRITICAL;
    }
}
