package com.proxy.interceptor.controller;

import com.proxy.interceptor.dto.ApiResponse;
import com.proxy.interceptor.dto.ApprovalRequest;
import com.proxy.interceptor.dto.VoteRequest;
import com.proxy.interceptor.model.BlockedQuery;
import com.proxy.interceptor.service.AuditService;
import com.proxy.interceptor.service.BlockedQueryService;
import com.proxy.interceptor.service.ReplayProtectionService;
import com.proxy.interceptor.util.RequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class QueryController {

    private final BlockedQueryService blockedQueryService;
    private final AuditService auditService;
    private final ReplayProtectionService replayProtectionService;

    @GetMapping("/blocked")
    public ResponseEntity<ApiResponse<List<BlockedQuery>>> getBlockedQueries() {
        return ResponseEntity.ok(ApiResponse.ok(blockedQueryService.getPendingQueries()));
    }

    @GetMapping("/blocked/all")
    public ResponseEntity<ApiResponse<List<BlockedQuery>>> getAllQueries() {
        return ResponseEntity.ok(ApiResponse.ok(blockedQueryService.getAllQueries()));
    }

    @GetMapping("/blocked/{id}/votes")
    public ResponseEntity<ApiResponse<?>> getVoteStatus(@PathVariable Long id) {
        var status = blockedQueryService.getVoteStatus(id);
        if (status == null) {
            return ResponseEntity.status(404).body(ApiResponse.error("Query or vote status not found"));
        }
        return ResponseEntity.ok(ApiResponse.ok(status));
    }

    @PostMapping("/approve")
    public ResponseEntity<ApiResponse<?>> approveQuery(
            @Valid @RequestBody ApprovalRequest request,
            HttpServletRequest httpRequest
    ) {
        String username = (String) httpRequest.getAttribute("username");
        String clientIp = RequestUtils.getClientIp(httpRequest);

        ResponseEntity<ApiResponse<?>> replayError = validateReplay(request, "approve", username, clientIp);
        if (replayError != null) return replayError;

        boolean ok = blockedQueryService.approveQuery(request.id(), username);

        if (ok) {
            auditService.log(username, "query_approved",
                    "Query #" + request.id() + " approved", clientIp);
        }

        return ResponseEntity.ok(ApiResponse.ok(Map.of("success", ok)));
    }

    @PostMapping("/reject")
    public ResponseEntity<ApiResponse<?>> rejectQuery(
            @Valid @RequestBody ApprovalRequest request,
            HttpServletRequest httpRequest
    ) {
        String username = (String) httpRequest.getAttribute("username");
        String clientIp = RequestUtils.getClientIp(httpRequest);

        ResponseEntity<ApiResponse<?>> replayError = validateReplay(request, "reject", username, clientIp);
        if (replayError != null) return replayError;

        boolean ok = blockedQueryService.rejectQuery(request.id(), username);

        if (ok) {
            auditService.log(username, "query_rejected",
                    "Query #" + request.id() + " rejected", clientIp);
        }

        return ResponseEntity.ok(ApiResponse.ok(Map.of("success", ok)));
    }

    @PostMapping("/vote")
    public ResponseEntity<ApiResponse<?>> vote(
            @Valid @RequestBody VoteRequest request,
            HttpServletRequest httpRequest
    ) {
        String username = (String) httpRequest.getAttribute("username");
        String clientIp = RequestUtils.getClientIp(httpRequest);

        ResponseEntity<ApiResponse<?>> replayError = validateVoteReplay(request, username, clientIp);
        if (replayError != null) return replayError;

        Map<String, Object> result = blockedQueryService.addVote(request.id(), username, request.vote());

        if (Boolean.TRUE.equals(result.get("duplicate"))) {
            return ResponseEntity.status(403).body(ApiResponse.error((String) result.get("error")));
        }

        auditService.log(username, "query_vote",
            String.format("Vote %s on query #%d", request.vote(), request.id()), clientIp);

        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /** Helper Methods for Replay Protection Deduplication */
    private ResponseEntity<ApiResponse<?>> validateReplay(ApprovalRequest request,
                                             String action,
                                             String username,
                                             String clientIp) {
        if (request.nonce() != null && request.timestamp() != null) {
            if (!replayProtectionService.validateRequest(
                    request.nonce(), request.timestamp(),
                    action + ":" + request.id(), username)) {
                auditService.log(username, "replay_attack_blocked",
                        "Attempted replay on " + action + " for query #" + request.id(), clientIp);
                return ResponseEntity.status(403).body(ApiResponse.error("Replay attack detected"));
            }
        }
        return null;
    }

    private ResponseEntity<ApiResponse<?>> validateVoteReplay(VoteRequest request,
                                                 String username,
                                                 String clientIp) {
        if (request.nonce() != null && request.timestamp() != null) {
            if (!replayProtectionService.validateRequest(
                    request.nonce(), request.timestamp(),
                    "vote:" + request.id() + ":" + request.vote(), username)) {
                auditService.log(username, "replay_attack_blocked",
                        "Attempted replay on vote for query #" + request.id(), clientIp);
                return ResponseEntity.status(403).body(ApiResponse.error("Replay attack detected"));
            }
        }
        return null;
    }
}