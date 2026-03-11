package com.proxy.interceptor.service;

import com.proxy.interceptor.config.ApprovalProperties;
import com.proxy.interceptor.config.RiskScoringProperties;
import com.proxy.interceptor.dto.PendingQuery;
import com.proxy.interceptor.dto.RiskAssessment;
import com.proxy.interceptor.messaging.QueryEventPublisher;
import com.proxy.interceptor.model.*;
import com.proxy.interceptor.repository.BlockedQueryRepository;
import com.proxy.interceptor.service.risk.DynamicRiskService;
import io.netty.buffer.ByteBuf;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
@RequiredArgsConstructor
@Slf4j
public class BlockedQueryService {

    private final BlockedQueryRepository blockedQueryRepository;
    private final QueryEventPublisher queryEventPublisher;
    private final AuditService auditService;
    private final ApprovalProperties approvalProperties;
    private final DynamicRiskService dynamicRiskService;
    private final RiskScoringProperties riskScoringProperties;

    // In-memory store for pending queries with their callbacks
    private final ConcurrentHashMap<Long, PendingQuery> pendingQueries = new ConcurrentHashMap<>();

    @Transactional
    public void addBlockedQuery(String connId,
                                String queryType,
                                String sql,
                                ByteBuf originalMessage,
                                Consumer<ByteBuf> forwardCallback,
                                Consumer<String> rejectCallback) {

        // Generate nonce for replay protection
        String nonce = UUID.randomUUID().toString();

        // Dynamic Risk Scoring
        int requiredApprovals;
        Double riskScore = null;

        if (riskScoringProperties.isEnabled()) {
            // Evaluate query risk dynamically
            // IP is not available at the proxy layer; pass connId context
            RiskAssessment riskAssessment = dynamicRiskService.evaluateQuery(sql, connId, "0.0.0.0");
            requiredApprovals = riskAssessment.requiredApprovals();
            riskScore = riskAssessment.riskScore();

            log.info("DRS: Query from {} scored R={} → {} approvals required",
                    connId, String.format("%.3f", riskScore), requiredApprovals);
        } else {
            // Fallback to static minVotes from config
            requiredApprovals = approvalProperties.getMinVotes();
        }

        // Save to database
        BlockedQuery query = BlockedQuery.builder()
                .connId(connId)
                .queryType(QueryType.valueOf(queryType))
                .queryPreview(sql.length() > 4000 ? sql.substring(0, 4000) : sql)
                .requiresPeerApproval(approvalProperties.isPeerEnabled())
                .requiredApprovals(requiredApprovals)
                .riskScore(riskScore)
                .nonce(nonce)
                .build();

        query = blockedQueryRepository.save(query);

        // Store in memory for callbacks
        PendingQuery pending = new PendingQuery(
                query.getId(),
                connId,
                originalMessage,
                forwardCallback,
                rejectCallback,
                ConcurrentHashMap.newKeySet(),
                ConcurrentHashMap.newKeySet()
        );
        pendingQueries.put(query.getId(), pending);

        // Publish notification to Redis for real-time updates
        queryEventPublisher.publishBlocked(query);

        log.info("Blocked query #{} from {}: {}", query.getId(), connId, sql.substring(0, Math.min(50, sql.length())));
    }

    @Transactional
    public boolean approveQuery(Long id, String approvedBy) {
        PendingQuery pending = pendingQueries.get(id);
        if (pending == null) {
            log.error("Approve failed: query #{} not found in pending", id);
            return false;
        }

        BlockedQuery query = blockedQueryRepository.findById(id).orElse(null);
        if (query == null || query.getStatus() != Status.PENDING) {
            log.error("Approve failed: query #{} not in pending status", id);
            return false;
        }

        // Update database
        query.setStatus(Status.APPROVED);
        query.setResolvedAt(Instant.now());
        query.setResolvedBy(approvedBy);
        blockedQueryRepository.save(query);

        // Forward the original query to PostgreSQL
        pending.forwardCallback().accept(pending.originalMessage());
        pendingQueries.remove(id);

        // Audit
        auditService.log(approvedBy, "query_approved",
                String.format("Query #%d approved: %s", id, query.getQueryPreview()), null);

        // Publish approval notification
        queryEventPublisher.publishApproval(query, "APPROVED", approvedBy);

        log.info("Query #{} approved by {}", id, approvedBy);
        return true;
    }

    @Transactional
    public boolean rejectQuery(Long id, String rejectedBy) {
        PendingQuery pending = pendingQueries.get(id);
        if (pending == null) {
            log.warn("Reject failed: query #{} not found in pending", id);
            return false;
        }

        BlockedQuery query = blockedQueryRepository.findById(id).orElse(null);
        if (query == null || query.getStatus() != Status.PENDING) {
            log.warn("Reject failed: query #{} not in pending status", id);
            return false;
        }

        // Update database
        query.setStatus(Status.REJECTED);
        query.setResolvedAt(Instant.now());
        query.setResolvedBy(rejectedBy);
        blockedQueryRepository.save(query);

        // Send error response to client
        pending.rejectCallback().accept("Query rejected by " + rejectedBy);
        pending.originalMessage().release();
        pendingQueries.remove(id);

        // Audit
        auditService.log(rejectedBy, "query_rejected",
                String.format("Query #%d rejected: %s", id, query.getQueryPreview()), null);

        // Publish rejection notification
        queryEventPublisher.publishApproval(query, "REJECTED", rejectedBy);

        log.info("Query #{} rejected by {}", id, rejectedBy);
        return true;
    }

    @Transactional
    public Map<String, Object> addVote(Long id, String username, String vote) {
        PendingQuery pending = pendingQueries.get(id);
        if (pending == null) {
            log.warn("Vote failed: query #{} not found in pending", id);
            return Map.of("success", false, "duplicate", false, "error", "Query not found");
        }

        BlockedQuery query = blockedQueryRepository.findById(id).orElse(null);
        if (query == null || !query.isRequiresPeerApproval()) {
            log.warn("Vote failed: query #{} does not require peer approval", id);
            return Map.of("success", false, "duplicate", false, "error", "Query does not require peer approval");
        }

        Vote voteEnum;
        try {
            voteEnum = Vote.valueOf(vote.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Map.of("success", false, "duplicate", false, "error", "Invalid vote type");
        }

        // Check if the user has already voted
        QueryApproval existingApproval = query.getApprovals().stream()
                .filter(a -> a.getUsername().equals(username))
                .findFirst()
                .orElse(null);

        if (existingApproval != null) {
            // User already voted
            if (existingApproval.getVote() == voteEnum) {
                log.info("User {} already voted {} on query #{}. Ignoring duplicate.", username, vote, id);
                return Map.of(
                        "success", false,
                        "duplicate", true,
                        "error", "You have already voted on this query"
                );
            } else {
                // User changed their vote
                log.info("User {} changed vote from {} to {} on query #{}",
                        username, existingApproval.getVote(), voteEnum, id);
                existingApproval.setVote(voteEnum);
                existingApproval.setVotedAt(Instant.now());
            }
        } else {
            // New vote
            QueryApproval approval = QueryApproval.builder()
                .blockedQuery(query)
                .username(username)
                .vote(voteEnum)
                .build();
            query.getApprovals().add(approval);
        }

        // Update In-Memory State (PendingQuery)
        if (voteEnum == Vote.APPROVE) {
            pending.approvals().add(username);
            pending.rejections().remove(username);
        } else {
            pending.rejections().add(username);
            pending.approvals().remove(username);
        }

        // Update counts
        query.setApprovalCount(pending.approvals().size());
        query.setRejectionCount(pending.rejections().size());

        // Save changes
        blockedQueryRepository.save(query);

        // Dynamic threshold check
        int threshold = query.getRequiredApprovals();

        if (pending.approvals().size() >= threshold) {
            approveQuery(id, "Peer Approval System");
            return Map.of("success", true, "duplicate", false, "autoResolved", true, "action", "approved");
        }

        if (pending.rejections().size() >= threshold) {
            rejectQuery(id, "Peer Approval System");
            return Map.of("success", true, "duplicate", false, "autoResolved", true, "action", "rejected");
        }

        // Publish vote notification
        queryEventPublisher.publishVote(id, username, vote);

        return Map.of(
                "success", true,
                "duplicate", false,
                "autoResolved", false,
                "approvalCount", pending.approvals().size(),
                "rejectionCount", pending.rejections().size()
        );
    }

    public List<BlockedQuery> getPendingQueries() {
        return blockedQueryRepository.findByStatusOrderByCreatedAtAsc(Status.PENDING);
    }

    public List<BlockedQuery> getAllQueries() {
        return blockedQueryRepository.findTop100ByOrderByCreatedAtDesc();
    }

    public void cleanupConnection(String connId) {
        pendingQueries.entrySet().removeIf(entry -> {
            if (entry.getValue().connId().equals(connId)) {
                entry.getValue().originalMessage().release();
                log.info("Cleaned up pending query #{} for disconnected connection {}",
                        entry.getKey(), connId);
                return true;
            }
            return false;
        });
    }

    public Map<String, Object> getVoteStatus(Long id) {
        PendingQuery pending = pendingQueries.get(id);
        if (pending == null) {
            return null;
        }
        return Map.of(
                "id", id,
                "approvals", new ArrayList<>(pending.approvals()),
                "rejections", new ArrayList<>(pending.rejections()),
                "approvalCount", pending.approvals().size(),
                "rejectionCount", pending.rejections().size()
        );
    }
}
