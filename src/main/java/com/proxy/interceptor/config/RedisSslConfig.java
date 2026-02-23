package com.proxy.interceptor.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.io.IOException;

@Configuration
@ConditionalOnProperty(name = "spring.data.redis.ssl.enabled", havingValue = "true")
@Slf4j
public class RedisSslConfig {

    @Value("${proxy.ssl.trust-store}")
    private Resource trustStoreResource;

    @Value("${proxy.ssl.trust-store-password}")
    private String trustStorePassword;

    @Value("${proxy.ssl.client-key-store}")
    private Resource clientKeyStoreResource;

    @Value("${proxy.ssl.client-key-store-password}")
    private String clientKeyStorePassword;

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port
    ) throws IOException {

        SslOptions sslOptions = SslOptions.builder()
                .jdkSslProvider()
                .keystore(clientKeyStoreResource.getFile(), clientKeyStorePassword.toCharArray())
                .truststore(trustStoreResource::getInputStream, trustStorePassword.toCharArray())
                .protocols("TLSv1.3")
                .cipherSuites("TLS_AES_256_GCM_SHA384")
                .build();

        ClientOptions clientOptions = ClientOptions.builder()
                .sslOptions(sslOptions)
                .build();

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .clientOptions(clientOptions)
                .useSsl()
                .build();

        RedisStandaloneConfiguration redisConfig =
                new RedisStandaloneConfiguration(host, port);

        log.info("Redis SSL connection factory configured with mTLS (TLSv1.3, TLS_AES_256_GCM_SHA384)");
        return new LettuceConnectionFactory(redisConfig, clientConfig);
    }
}