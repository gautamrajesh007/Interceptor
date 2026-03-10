package com.proxy.interceptor.proxy.ast;

public interface SqlAnalyzer {
    /**
     * Parses the SQL string and returns an analysis of its structure.
     */
    SqlAnalysisResult analyze(String sql);
}