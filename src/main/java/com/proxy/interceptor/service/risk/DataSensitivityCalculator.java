package com.proxy.interceptor.service.risk;

import com.proxy.interceptor.config.RiskScoringProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calculates the Data Sensitivity Score S_data ∈ [0, 1].
 * <p>
 * Formula: S_data = max(Sensitivity(table_i)) for all tables accessed.
 * <p>
 * Uses JSqlParser TablesNamesFinder for accurate table extraction,
 * with regex fallback.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataSensitivityCalculator {

    private final RiskScoringProperties properties;

    // Regex to extract table names from common SQL patterns
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "(?:FROM|JOIN|INTO|UPDATE|TABLE)\\s+(?:IF\\s+EXISTS\\s+)?([a-zA-Z_][a-zA-Z0-9_.]*)",
            Pattern.CASE_INSENSITIVE
    );

    public double calculate(String sql) {
        List<String> tables = extractTables(sql);
        Map<String, Double> sensitivityMap = properties.getSensitivityMap();

        double maxSensitivity = 0.0;

        for (String table : tables) {
            String normalizedTable = table.toLowerCase().trim();
            // Remove schema prefix if present (e.g., "public.users" -> "users")
            if (normalizedTable.contains(".")) {
                normalizedTable = normalizedTable.substring(normalizedTable.lastIndexOf('.') + 1);
            }

            // Exact match
            if (sensitivityMap.containsKey(normalizedTable)) {
                maxSensitivity = Math.max(maxSensitivity, sensitivityMap.get(normalizedTable));
            } else {
                // Partial match — check if any key is contained in the table name
                for (Map.Entry<String, Double> entry : sensitivityMap.entrySet()) {
                    if (normalizedTable.contains(entry.getKey())) {
                        maxSensitivity = Math.max(maxSensitivity, entry.getValue());
                    }
                }
            }
        }

        // Also scan for sensitive column references in the SQL text
        String lowerSql = sql.toLowerCase();
        for (Map.Entry<String, Double> entry : sensitivityMap.entrySet()) {
            if (lowerSql.contains(entry.getKey())) {
                maxSensitivity = Math.max(maxSensitivity, entry.getValue());
            }
        }

        return Math.min(1.0, maxSensitivity);
    }

    private List<String> extractTables(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            TablesNamesFinder<List<String>> finder = new TablesNamesFinder<>();
            return new ArrayList<>(finder.getTables(statement));
        } catch (Exception e) {
            log.debug("JSqlParser table extraction failed, using regex fallback: {}", e.getMessage());
            return extractTablesRegex(sql);
        }
    }

    private List<String> extractTablesRegex(String sql) {
        List<String> tables = new ArrayList<>();
        Matcher matcher = TABLE_PATTERN.matcher(sql);
        while (matcher.find()) {
            tables.add(matcher.group(1));
        }
        return tables;
    }

}