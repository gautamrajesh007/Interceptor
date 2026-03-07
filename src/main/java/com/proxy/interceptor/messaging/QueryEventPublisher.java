package com.proxy.interceptor.messaging;

import com.proxy.interceptor.model.BlockedQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class QueryEventPublisher {

    private final RedisTemplate<String, Object> redisTemplate;

    public void publishBlocked(BlockedQuery query) {
        redisTemplate.convertAndSend("interceptor:blocked", Map.of(
                "id", query.getId(),
                "connId", query.getConnId(),
                "queryType", query.getQueryType().name(),
                "queryPreview", query.getQueryPreview(),
                "status", query.getStatus().name()
        ));
    }

    public void publishApproval(BlockedQuery query, String action, String resolvedBy) {
        redisTemplate.convertAndSend("interceptor:approvals", Map.of(
                "id", query.getId(),
                "status", action,
                "resolvedBy", resolvedBy
        ));
    }

    public void publishVote(Long queryId, String username, String vote) {
        redisTemplate.convertAndSend("interceptor:votes", Map.of(
                "queryId", queryId,
                "voter", username,
                "vote", vote
        ));
    }
}