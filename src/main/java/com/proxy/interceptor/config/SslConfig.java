package com.proxy.interceptor.config;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;

@Configuration
@Slf4j
public class SslConfig {

    @Getter
    @Value("${proxy.ssl.enabled}")
    private boolean sslEnabled;

    @Value("${proxy.ssl.key-store}")
    private String keyStorePath;

    @Value("${proxy.ssl.key-store-password}")
    private String keyStorePassword;

    @Value("${proxy.ssl.key-store-type}")
    private String keyStoreType;

    @Value("${proxy.ssl.trust-store}")
    private String trustStorePath;

    @Value("${proxy.ssl.trust-store-password}")
    private String trustStorePassword;

    @Value("${proxy.ssl.trust-store-type}")
    private String trustStoreType;

    @Value("${proxy.ssl.client-auth}")
    private boolean clientAuth;

    private final ResourceLoader resourceLoader;

    public SslConfig(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /*
    * Create SSL context for proxy server (accepting client connections
     */
    @Bean(name = "proxySslContext")
    public SslContext proxySslContext() throws Exception {
        if (!sslEnabled) {
            log.info("Proxy ssl is disabled");
            return null;
        }

        log.info("Configuring proxy SSL context...");

        KeyManagerFactory keyManagerFactory = loadKeyManagerFactory();
        TrustManagerFactory trustManagerFactory = loadTrustManagerFactory();

        SslContextBuilder builder = SslContextBuilder.forServer(keyManagerFactory)
                .sslProvider(SslProvider.JDK);

        if (trustManagerFactory != null) {
            builder.trustManager(trustManagerFactory);
        }

        if (clientAuth) {
            builder.clientAuth(ClientAuth.REQUIRE);
            log.info("Proxy SSL: Client certificate authentication REQUIRED");
        } else {
            builder.clientAuth(ClientAuth.NONE);
            log.info("Proxy SSL: Client certificate authentication DISABLED");
        }

        SslContext sslContext = builder.build();
        log.info("✅ Proxy SSL context configured successfully");
        return sslContext;
    }

    /**
     * Creates SSL context for  the PostgreSQL server (as a client).
     */
    @Bean(name = "postgresClientSslContext")
    public SslContext postgresClientSslContext() throws Exception {
        if (!sslEnabled) {
            return null;
        }

        log.info("Configuring PostgreSQL client SSL context...");

        KeyManagerFactory keyManagerFactory = loadKeyManagerFactory();
        TrustManagerFactory trustManagerFactory = loadTrustManagerFactory();

        SslContextBuilder builder = SslContextBuilder.forClient()
                .sslProvider(SslProvider.JDK);

        if (keyManagerFactory != null) {
            builder.keyManager(keyManagerFactory);
        }

        if (trustManagerFactory != null) {
            builder.trustManager(trustManagerFactory);
        } else {
            // For development: trust all certificates (Handle properly in production!)
            log.warn("⚠️ Using insecure trust manager");
            builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
        }

        SslContext sslContext = builder.build();
        log.info("✅ PostgreSQL client SSL context configured successfully");
        return sslContext;
    }

    private KeyManagerFactory loadKeyManagerFactory() throws Exception {
        if (keyStorePath == null || keyStorePath.isBlank()) {
            return null;
        }

        Resource keyStoreResource = resourceLoader.getResource(keyStorePath);
        KeyStore keyStore = KeyStore.getInstance(keyStoreType);

        try (InputStream is = keyStoreResource.getInputStream()) {
            keyStore.load(is, keyStorePassword.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keyStorePassword.toCharArray());

        log.debug("Loaded key store from: {}", keyStorePath);
        return kmf;
    }

    private TrustManagerFactory loadTrustManagerFactory() throws Exception {
        if (trustStorePath == null || trustStorePath.isBlank()) {
            return null;
        }

        Resource trustStoreResource = resourceLoader.getResource(trustStorePath);
        KeyStore trustStore = KeyStore.getInstance(trustStoreType);

        try (InputStream is = trustStoreResource.getInputStream()) {
            trustStore.load(is, trustStorePassword.toCharArray());
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        log.debug("Loaded trust store from: {}", trustStorePath);
        return tmf;
    }

}
