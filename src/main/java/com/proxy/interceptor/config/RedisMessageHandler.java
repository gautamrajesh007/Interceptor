package com.proxy.interceptor.config;

import com.proxy.interceptor.service.WebSocketNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisMessageHandler {

    private final WebSocketNotificationService webSocketNotificationService;

    public void handleBlockedMessage(String message) {
        log.debug("Redis blocked message: {}", message);
        webSocketNotificationService.broadcastBlockedQuery(message);
    }

    public void handleApprovalMessage(String message) {
        log.debug("Redis approval message: {}", message);
        webSocketNotificationService.broadcastApproval(message);
    }

    public void handleVoteMessage(String message) {
        log.debug("Redis vote message: {}", message);
        webSocketNotificationService.broadcastVote(message);
    }
}
