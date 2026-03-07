package com.proxy.interceptor.proxy;

import com.proxy.interceptor.config.SslContextFactory;
import com.proxy.interceptor.service.BlockedQueryService;
import com.proxy.interceptor.service.MetricsService;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Groups all shared dependencies needed by proxy channel handlers.
 * Reduces constructor parameter count from 10+ to 1 context object.
 */
public record ProxyContext(
        String targetHost,
        int targetPort,
        SqlClassifier sqlClassifier,
        WireProtocolHandler protocolHandler,
        BlockedQueryService blockedQueryService,
        MetricsService metricsService,
        EventLoopGroupFactory eventLoopGroupFactory,
        SslContextFactory sslContextFactory,
        ConcurrentHashMap<String, ConnectionState> connections
) {}