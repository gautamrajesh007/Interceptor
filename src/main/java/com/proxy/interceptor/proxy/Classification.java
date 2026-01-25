package com.proxy.interceptor.proxy;

public enum Classification {
    CRITICAL, // Requires approval
    ALLOWED, // Pass through
    DEFAULT // Follows block-by-default policy
}
