// src/main/java/com/proxy/interceptor/util/NetworkUtils.java
package com.proxy.interceptor.util;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Slf4j
public final class NetworkUtils {

    private NetworkUtils() {}

    /**
     * Gets the IP address of the system running the proxy.
     */
    public static String getSystemIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            log.warn("Could not determine system IP, defaulting to localhost", e);
            return "127.0.0.1";
        }
    }
    
    /**
     * Future helper for Risk Scoring: Checks if the client IP shares the same subnet
     * (e.g., simplistic check matching the first 3 octets for a /24 subnet).
     */
    public static boolean isSameSubnet(String clientIp, String systemIp) {
        if (clientIp == null || systemIp == null) return false;
        if (clientIp.equals("127.0.0.1") || clientIp.equals("0:0:0:0:0:0:0:1")) return true;
        
        int lastDotClient = clientIp.lastIndexOf('.');
        int lastDotSystem = systemIp.lastIndexOf('.');
        
        if (lastDotClient == -1 || lastDotSystem == -1) return false; // IPv6 or malformed fallback
        
        return clientIp.substring(0, lastDotClient).equals(systemIp.substring(0, lastDotSystem));
    }
}