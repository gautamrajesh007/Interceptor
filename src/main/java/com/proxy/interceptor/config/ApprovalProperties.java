package com.proxy.interceptor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "approval")
@Getter
@Setter
public class ApprovalProperties {
    private boolean peerEnabled;
    private int minVotes;
}