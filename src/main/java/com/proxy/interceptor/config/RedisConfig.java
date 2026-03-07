package com.proxy.interceptor.config;

import com.proxy.interceptor.messaging.RedisMessageHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Key serializers
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // Value serializers
        GenericJacksonJsonRedisSerializer serializer =
                GenericJacksonJsonRedisSerializer
                        .builder()
                        .enableUnsafeDefaultTyping()
                        .build();

        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);
        return template;
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter blockedListener,
            MessageListenerAdapter approvalListener,
            MessageListenerAdapter voteListener
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(blockedListener, new PatternTopic("interceptor:blocked"));
        container.addMessageListener(approvalListener, new PatternTopic("interceptor:approvals"));
        container.addMessageListener(voteListener, new PatternTopic("interceptor:votes"));
        return container;
    }

    @Bean
    public MessageListenerAdapter blockedListener(RedisMessageHandler handler) {
        return new MessageListenerAdapter(handler, "handleBlockedMessage");
    }

    @Bean
    public MessageListenerAdapter approvalListener(RedisMessageHandler handler) {
        return new MessageListenerAdapter(handler, "handleApprovalMessage");
    }

    @Bean
    public MessageListenerAdapter voteListener(RedisMessageHandler handler) {
        return new MessageListenerAdapter(handler, "handleVoteMessage");
    }
}
