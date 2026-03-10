package com.proxy.interceptor.proxy.ast;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class JSqlParserAnalyzer implements SqlAnalyzer {

    @Override
    public SqlAnalysisResult analyze(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            String operation = extractOperationType(statement);

            // astDepth and joinCount default to 0 for now (to be implemented in Dynamic Risk Scoring phase)
            return new SqlAnalysisResult(operation, true, null, 0, 0);
        } catch (Exception e) {
            log.debug("AST Parsing failed for SQL. Falling back to simple parsing. Error: {}", e.getMessage());
            return new SqlAnalysisResult("UNKNOWN", false, e.getMessage(), 0, 0);
        }
    }

    private String extractOperationType(Statement statement) {
        // Map common JSqlParser statements to your properties configuration keywords
        if (statement instanceof Select) return "SELECT";
        if (statement instanceof Insert) return "INSERT";
        if (statement instanceof Update) return "UPDATE";
        if (statement instanceof Delete) return "DELETE";
        if (statement instanceof Drop) return "DROP";
        if (statement instanceof Alter) return "ALTER";
        if (statement instanceof Truncate) return "TRUNCATE";
        if (statement instanceof CreateTable) return "CREATE";

        // Fallback for Grant, Revoke, etc. (Strips the class name to uppercase)
        return statement.getClass().getSimpleName().toUpperCase();
    }
}