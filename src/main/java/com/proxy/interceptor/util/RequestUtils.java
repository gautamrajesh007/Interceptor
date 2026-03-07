package com.proxy.interceptor.util;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestUtils {

    private RequestUtils() {}

    /**
     * Extract client IP, respecting X-Forwarded-For for reverse proxies.
     */
    public static String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Extract authenticated username from request attributes (set by JwtAuthFilter).
     */
    public static String getUsername(HttpServletRequest request) {
        return (String) request.getAttribute("username");
    }
}