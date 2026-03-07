package com.proxy.interceptor.config;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.Collections;

@Component
@ConditionalOnProperty(name = "proxy.ssl.enabled", havingValue = "true")
@Slf4j
public class SslContextFactory {

    // ── Backend (Proxy → DB) trust store ──
    @Value("${proxy.ssl.trust-store}")
    private Resource trustStoreResource;

    @Value("${proxy.ssl.trust-store-password}")
    private String trustStorePassword;

    // ── Frontend (Client → Proxy) key store — the proxy's own server identity ──
    @Value("${proxy.ssl.server-key-store}")
    private Resource serverKeyStoreResource;

    @Value("${proxy.ssl.server-key-store-password}")
    private String serverKeyStorePassword;

    // TLS client context: proxy authenticates to the database
    private SslContext backendSslContext;

    // TLS server context: proxy presents certificate to psql/DataGrip clients
    private SslContext frontendSslContext;

    @PostConstruct
    public void init() throws Exception {
        initBackendContext();
        initFrontendContext();
    }

    // ────────────────────── Backend (Proxy → PostgreSQL) ──────────────────────

    private void initBackendContext() throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = trustStoreResource.getInputStream()) {
            trustStore.load(is, trustStorePassword.toCharArray());
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        this.backendSslContext = SslContextBuilder.forClient()
                .sslProvider(SslProvider.JDK)
                .trustManager(tmf)
                .protocols("TLSv1.3")
                .ciphers(Collections.singletonList("TLS_AES_256_GCM_SHA384"),
                        SupportedCipherSuiteFilter.INSTANCE)
                .build();

        log.info("Backend SSL context initialized (TLSv1.3, TLS_AES_256_GCM_SHA384)");
    }

    // ────────────────────── Frontend (Client → Proxy) ──────────────────────

    private void initFrontendContext() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream is = serverKeyStoreResource.getInputStream()) {
            keyStore.load(is, serverKeyStorePassword.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, serverKeyStorePassword.toCharArray());

        this.frontendSslContext = SslContextBuilder.forServer(kmf)
                .sslProvider(SslProvider.JDK)
                .protocols("TLSv1.3")
                .ciphers(Collections.singletonList("TLS_AES_256_GCM_SHA384"),
                        SupportedCipherSuiteFilter.INSTANCE)
                .build();

        log.info("Frontend SSL context initialized (TLSv1.3, TLS_AES_256_GCM_SHA384)");
    }

    // ────────────────────── Handler factories ���─────────────────────

    /**
     * Create an SslHandler for the backend channel (proxy acts as TLS client to DB).
     * The host/port are used for hostname verification.
     */
    public SslHandler newBackendHandler(ByteBufAllocator alloc, String host, int port) {
        return backendSslContext.newHandler(alloc, host, port);
    }

    /**
     * Create an SslHandler for the frontend channel (proxy acts as TLS server to psql/DataGrip).
     */
    public SslHandler newFrontendHandler(ByteBufAllocator alloc) {
        return frontendSslContext.newHandler(alloc);
    }

    /**
     * @deprecated Use {@link #newBackendHandler(ByteBufAllocator, String, int)} instead.
     * Kept for backward compatibility during transition.
     */
    @Deprecated
    public SslHandler newHandler(ByteBufAllocator alloc, String host, int port) {
        return newBackendHandler(alloc, host, port);
    }
}
