package com.proxy.interceptor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebSocketNotificationService {

    private final SimpMessagingTemplate messagingTemplate;

    public void broadcastBlockedQuery(String message) {
        messagingTemplate.convertAndSend("/topic/blocked", message);
    }

    public void broadcastApproval(String message) {
        messagingTemplate.convertAndSend("/topic/approvals", message);
    }

    public void broadcastVote(String message) {
        messagingTemplate. convertAndSend("/topic/votes", message);
    }

    public void broadcastLog(String message) {
        messagingTemplate.convertAndSend("/topic/logs", message);
    }

    public void broadcastMetrics(Object metrics) {
        messagingTemplate.convertAndSend("/topic/metrics", metrics);
    }
}
