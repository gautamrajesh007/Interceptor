package com.proxy.interceptor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "proxy")
@Getter
@Setter
public class ProxyProperties {
    private int listenPort;
    private String targetHost;
    private int targetPort;
    private boolean blockByDefault;
    private List<String> criticalKeywords;
    private List<String> allowedKeywords;

    private Ssl ssl = new Ssl();

    @Getter
    @Setter
    public static class Ssl {
        private boolean enabled;
    }
}