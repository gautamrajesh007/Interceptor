package com.proxy.interceptor.proxy.ast;

public record SqlAnalysisResult(
        String operationType,
        boolean parseSuccess,
        String errorMessage,
        // Placeholders for future Dynamic Risk Scoring
        int astDepth,
        int joinCount
) {}