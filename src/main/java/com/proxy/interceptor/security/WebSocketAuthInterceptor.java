package com.proxy.interceptor.security;

import com.proxy.interceptor.dto.TokenClaims;
import com.proxy.interceptor.repository.UserRepository;
import com.proxy.interceptor.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            authenticateFromHeader(authHeader, accessor);
        }
        return message;
    }

    private void authenticateFromHeader(String authHeader, StompHeaderAccessor accessor) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            auditService.log("UNKNOWN", "websocket_auth_missing_token",
                    "WebSocket connection attempt without Authorization header",
                    null);
            return;
        }

        String token = authHeader.substring(7);
        if (!jwtTokenProvider.validateToken(token)) {
            auditService.log("UNKNOWN", "websocket_auth_invalid_token",
                    "WebSocket connection attempt with invalid token",
                    null);
            return;
        }

        TokenClaims claims = jwtTokenProvider.parseToken(token);

        String username = claims.username();
        String role = claims.role();
        Integer tokenVersion = claims.tokenVersion();

        userRepository.findByUsername(username).ifPresentOrElse(user -> {
            Integer dbVersion = user.getTokenVersion() != null ? user.getTokenVersion() : 0;
            Integer jwtVersion = tokenVersion != null ? tokenVersion : 0;

            if (dbVersion.equals(jwtVersion)) {
                var auth = new UsernamePasswordAuthenticationToken(
                        username, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))
                );
                accessor.setUser(auth);
                log.debug("WebSocket authenticated: {}", username);

                auditService.log(username, "websocket_connected",
                        "WebSocket connection established",
                        null);
            } else {
                log.warn("WebSocket Auth blocked: Token version mismatch for {}", username);
                auditService.log(username, "websocket_auth_token_version_mismatch",
                        String.format("WebSocket connection blocked: Token version mismatch (JWT: %d, DB: %d)",
                                jwtVersion, dbVersion),
                        null);
            }
        }, () -> {
            log.warn("WebSocket Auth blocked: User not found: {}", username);
            auditService.log(username, "websocket_auth_user_not_found",
                    "WebSocket connection blocked: User not found",
                    null);
        });
    }
}