package com.proxy.interceptor.config;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.Collections;

@Component
@ConditionalOnProperty(name = "proxy.ssl.enabled", havingValue = "true")
@Slf4j
public class SslContextFactory {

    @Value("${proxy.ssl.trust-store}")
    private Resource trustStoreResource;

    @Value("${proxy.ssl.trust-store-password}")
    private String trustStorePassword;

    private SslContext sslContext;

    @PostConstruct
    public void init() throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = trustStoreResource.getInputStream()) {
            trustStore.load(is, trustStorePassword.toCharArray());
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        this.sslContext = SslContextBuilder.forClient()
                .sslProvider(SslProvider.JDK)
                .trustManager(tmf)
                .protocols("TLSv1.3")
                .ciphers(Collections.singletonList("TLS_AES_256_GCM_SHA384"),
                        SupportedCipherSuiteFilter.INSTANCE)
                .build();

        log.info("Proxy SSL context initialized (TLSv1.3, TLS_AES_256_GCM_SHA384)");
    }

    public SslHandler newHandler(ByteBufAllocator alloc, String host, int port) {
        return sslContext.newHandler(alloc, host, port);
    }
}
