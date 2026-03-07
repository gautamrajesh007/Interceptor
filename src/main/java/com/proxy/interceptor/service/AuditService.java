package com.proxy.interceptor.service;

import com.proxy.interceptor.model.AuditLog;
import com.proxy.interceptor.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    @Value("${audit.retention-days}")
    private int retentionDays;

    public void log(String username, String action, String details, String ipAddress) {
        AuditLog auditLog = AuditLog.builder()
                .username(username != null ? username : "SYSTEM")
                .action(action)
                .details(details)
                .ipAddress(ipAddress)
                .build();

        auditLogRepository.save(auditLog);
        log.debug("Audit: {} - {} - {}", username, action, details);
    }

    public void logWithHash(String username, String action, String details, String ipAddress, String requestHash) {
        AuditLog auditLog = AuditLog.builder()
                .username(username != null ? username : "SYSTEM")
                .action(action)
                .details(details)
                .ipAddress(ipAddress)
                .requestHash(requestHash)
                .build();

        auditLogRepository.save(auditLog);
    }

    public List<AuditLog> getRecentLogs() {
        return auditLogRepository.findTop100ByOrderByTimestampDesc();
    }

    public List<AuditLog> getLogsByUser(String username) {
        return auditLogRepository.findByUsernameOrderByTimestampDesc(username);
    }

    // Cleanup old audit logs
    @Scheduled(cron = "0 0 2 * * ? ") // Run at 2 AM daily
    @Transactional
    public void cleanupOldLogs() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        auditLogRepository.deleteByTimestampBefore(cutoff);
        log.info("Cleaned up audit logs older than {} days", retentionDays);
    }
}
