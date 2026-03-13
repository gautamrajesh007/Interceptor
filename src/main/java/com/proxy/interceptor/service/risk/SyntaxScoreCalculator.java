package com.proxy.interceptor.service.risk;

import com.proxy.interceptor.config.RiskScoringProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Component;

/**
 * Calculates the Syntax Complexity Score S_syn ∈ [0, 1].
 * <p>
 * Formula: S_syn = min(1.0, (C_depth * D + C_joins * J + Σ P_ops) / 10)
 * <p>
 * Uses JSqlParser (already in pom.xml) for AST-based analysis:
 * - D = subquery nesting depth
 * - J = number of JOIN clauses
 * - P_ops = fixed penalties for dangerous operations
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SyntaxScoreCalculator {

    private final RiskScoringProperties properties;

    public double calculate(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            return calculateFromAST(statement, sql);
        } catch (Exception e) {
            log.warn("JSqlParser failed for SQL, falling back to regex analysis: {}", e.getMessage());
            return calculateFallback(sql);
        }
    }

    private double calculateFromAST(Statement statement, String sql) {
        double operationPenalty = getOperationPenalty(statement, sql);
        int joinCount = 0;
        int depth = 0;

        if (statement instanceof Select select) {
            joinCount = countJoins(select);
            depth = measureDepth(select);
        }

        double raw = (properties.getDepthCoefficient() * depth)
                + (properties.getJoinCoefficient() * joinCount)
                + operationPenalty;

        return Math.min(1.0, raw / 10.0);
    }

    private double getOperationPenalty(Statement statement, String sql) {
        String upper = sql.toUpperCase().trim();

        // DROP / TRUNCATE → maximum penalty
        if (statement instanceof Drop || statement instanceof Truncate) {
            return 10.0; // guarantees S_syn = 1.0
        }

        // DELETE without WHERE
        if (statement instanceof Delete delete) {
            if (delete.getWhere() == null) return 8.0;
            return 3.0;
        }

        // UPDATE without WHERE
        if (statement instanceof Update update) {
            if (update.getWhere() == null) return 8.0;
            return 2.0;
        }

        // INSERT ... SELECT (bulk insert from query)
        if (statement instanceof Insert insert) {
            if (insert.getSelect() != null) return 2.0;
            return 0.5;
        }

        // SELECT *
        if (statement instanceof Select) {
            if (upper.contains("SELECT *") || upper.contains("SELECT  *")) {
                return 3.0;
            }
            return 0.0;
        }

        // GRANT / REVOKE / ALTER — detected via string since JSqlParser may not parse all DDL
        if (upper.startsWith("GRANT") || upper.startsWith("REVOKE")) return 9.0;
        if (upper.startsWith("ALTER")) return 6.0;

        return 1.0; // unknown statement type — moderate baseline
    }

    private int countJoins(Select select) {
        int count = 0;
        if (select.getPlainSelect() != null) {
            PlainSelect ps = select.getPlainSelect();
            if (ps.getJoins() != null) {
                count += ps.getJoins().size();
            }
            // Count joins in subqueries within FROM
            count += countJoinsInSubSelects(ps);
        }
        return count;
    }

    private int countJoinsInSubSelects(PlainSelect ps) {
        int count = 0;
        if (ps.getFromItem() instanceof ParenthesedSelect sub) {
            if (sub.getSelect() != null && sub.getSelect().getPlainSelect() != null) {
                PlainSelect subPs = sub.getSelect().getPlainSelect();
                if (subPs.getJoins() != null) {
                    count += subPs.getJoins().size();
                }
                count += countJoinsInSubSelects(subPs);
            }
        }
        return count;
    }

    private int measureDepth(Select select) {
        if (select.getPlainSelect() != null) {
            return measurePlainSelectDepth(select.getPlainSelect(), 0);
        }
        return 0;
    }

    private int measurePlainSelectDepth(PlainSelect ps, int currentDepth) {
        int maxDepth = currentDepth;

        // Check WHERE for subqueries
        if (ps.getWhere() != null) {
            maxDepth = Math.max(maxDepth, measureExpressionDepth(ps.getWhere(), currentDepth));
        }

        // Check FROM subquery
        if (ps.getFromItem() instanceof ParenthesedSelect sub) {
            if (sub.getSelect() != null && sub.getSelect().getPlainSelect() != null) {
                maxDepth = Math.max(maxDepth,
                        measurePlainSelectDepth(sub.getSelect().getPlainSelect(), currentDepth + 1));
            }
        }

        return maxDepth;
    }

    private int measureExpressionDepth(Expression expr, int currentDepth) {
        // Simple heuristic: count nested SELECT keywords in the expression string
        String exprStr = expr.toString().toUpperCase();
        int count = 0;
        int idx = 0;
        while ((idx = exprStr.indexOf("SELECT", idx)) != -1) {
            count++;
            idx += 6;
        }
        return currentDepth + count;
    }

    /**
     * Regex-based fallback when JSqlParser cannot parse the SQL.
     */
    private double calculateFallback(String sql) {
        String upper = sql.toUpperCase().trim();

        if (upper.contains("DROP") || upper.contains("TRUNCATE")) return 1.0;
        if (upper.contains("GRANT") || upper.contains("REVOKE")) return 0.9;
        if (upper.contains("ALTER")) return 0.6;

        double score = 0.0;

        // Count JOINs
        int joins = countOccurrences(upper, "JOIN");
        score += joins * properties.getJoinCoefficient();

        // Count subquery depth (nested SELECT)
        int selects = countOccurrences(upper, "SELECT");
        if (selects > 1) {
            score += (selects - 1) * properties.getDepthCoefficient();
        }

        // UPDATE/DELETE without WHERE
        if ((upper.startsWith("UPDATE") || upper.startsWith("DELETE")) && !upper.contains("WHERE")) {
            score += 0.8;
        }

        // SELECT *
        if (upper.contains("SELECT *")) {
            score += 0.3;
        }

        return Math.min(1.0, score);
    }

    private int countOccurrences(String text, String keyword) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(keyword, idx)) != -1) {
            count++;
            idx += keyword.length();
        }
        return count;
    }
}