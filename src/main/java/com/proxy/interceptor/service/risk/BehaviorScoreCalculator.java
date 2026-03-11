package com.proxy.interceptor.service.risk;

import com.proxy.interceptor.model.BlockedQuery;
import com.proxy.interceptor.repository.BlockedQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Calculates the Behavioral Baseline Score S_beh ∈ [0, 1].
 * <p>
 * Uses TF-IDF-inspired cosine distance between the current query's
 * keyword vector and the historical centroid vector for the user's
 * connection. When no history exists, returns 0.5 (neutral).
 * <p>
 * Formula: S_beh = 1 - cos_sim(q_current, q_historical_centroid)
 * <p>
 * Future enhancement: replace with pgvector embeddings for
 * production-grade semantic similarity.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BehaviorScoreCalculator {

    private final BlockedQueryRepository blockedQueryRepository;

    // SQL keywords to use as feature dimensions
    private static final List<String> FEATURE_KEYWORDS = List.of(
            "select", "insert", "update", "delete", "drop", "alter", "create",
            "join", "where", "group", "order", "having", "limit", "union",
            "from", "into", "set", "values", "index", "grant", "revoke",
            "truncate", "begin", "commit", "rollback", "explain", "like"
    );

    public double calculate(String sql, String connId) {
        // Get recent queries from this connection for behavioral baseline
        List<BlockedQuery> history = blockedQueryRepository
                .findByConnIdAndStatusOrderByCreatedAtDesc(connId, com.proxy.interceptor.model.Status.APPROVED);

        if (history.isEmpty()) {
            // No history — return neutral score (unknown user behavior)
            return 0.5;
        }

        // Build current query vector
        Map<String, Double> currentVector = buildKeywordVector(sql);

        // Build centroid vector from historical queries
        Map<String, Double> centroidVector = buildCentroidVector(history);

        // Compute cosine similarity
        double cosineSimilarity = cosineSimilarity(currentVector, centroidVector);

        // S_beh = 1 - cosine_similarity
        return Math.max(0.0, Math.min(1.0, 1.0 - cosineSimilarity));
    }

    private Map<String, Double> buildKeywordVector(String sql) {
        String lower = sql.toLowerCase();
        Map<String, Double> vector = new HashMap<>();

        for (String keyword : FEATURE_KEYWORDS) {
            double count = countOccurrences(lower, keyword);
            vector.put(keyword, count);
        }

        return vector;
    }

    private Map<String, Double> buildCentroidVector(List<BlockedQuery> history) {
        // Limit to most recent 50 queries for performance
        List<BlockedQuery> recent = history.stream().limit(50).toList();
        Map<String, Double> centroid = new HashMap<>();

        for (String keyword : FEATURE_KEYWORDS) {
            centroid.put(keyword, 0.0);
        }

        for (BlockedQuery query : recent) {
            Map<String, Double> vec = buildKeywordVector(query.getQueryPreview());
            for (Map.Entry<String, Double> entry : vec.entrySet()) {
                centroid.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }

        // Average
        for (String keyword : FEATURE_KEYWORDS) {
            centroid.put(keyword, centroid.get(keyword) / recent.size());
        }

        return centroid;
    }

    private double cosineSimilarity(Map<String, Double> a, Map<String, Double> b) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        Set<String> allKeys = new HashSet<>(a.keySet());
        allKeys.addAll(b.keySet());

        for (String key : allKeys) {
            double valA = a.getOrDefault(key, 0.0);
            double valB = b.getOrDefault(key, 0.0);
            dotProduct += valA * valB;
            normA += valA * valA;
            normB += valB * valB;
        }

        if (normA == 0.0 || normB == 0.0) return 0.0;

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private double countOccurrences(String text, String keyword) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(keyword, idx)) != -1) {
            count++;
            idx += keyword.length();
        }
        return count;
    }
}