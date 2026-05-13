package com.proxy.interceptor.security;

import com.proxy.interceptor.dto.TokenClaims;
import com.proxy.interceptor.model.User;
import com.proxy.interceptor.repository.UserRepository;
import com.proxy.interceptor.service.AuditService;
import com.proxy.interceptor.util.RequestUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        String clientIp = RequestUtils.getClientIp(request);
        String requestUri = request.getRequestURI();
        log.debug("Processing request: {} {}", request.getMethod(), requestUri);

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            if (jwtTokenProvider.validateToken(token)) {
                TokenClaims claims = jwtTokenProvider.parseToken(token);
                String username = claims.username();
                String role = claims.role();
                Integer tokenVersion = claims.tokenVersion();

                Optional<User> userOpt = userRepository.findByUsername(username);

                if (userOpt.isPresent()) {
                    Integer dbVersion = userOpt.get().getTokenVersion() != null ? userOpt.get().getTokenVersion() : 0;
                    Integer jwtVersion = tokenVersion != null ? tokenVersion : 0;

                    if (dbVersion.equals(jwtVersion)) {
                        // Set username as request attribute for use in controllers
                        request.setAttribute("username", username);
                        request.setAttribute("role", role);

                        var auth = new UsernamePasswordAuthenticationToken(
                                username,
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + role))
                        );
                        SecurityContextHolder.getContext().setAuthentication(auth);
                        log.info("Authenticated user: {}, role: {}, token version: {}", username, role, tokenVersion);
                    } else {
                        log.warn("Token version mismatch for user {}", username);
                        auditService.log(username, "auth_token_version_mismatch",
                                String.format("Token version mismatch for user %s (JWT: %d, DB: %d) on %s",
                                        username, jwtVersion, dbVersion, requestUri),
                                clientIp);
                    }
                } else {
                    log.warn("User not found: {}", username);
                    auditService.log(username, "auth_user_not_found",
                            String.format("User not found: %s on %s", username, requestUri),
                            clientIp);
                }
            } else {
                log.warn("Invalid JWT token");
                auditService.log("UNKNOWN", "auth_invalid_token",
                        String.format("Invalid JWT token on %s", requestUri),
                        clientIp);
            }
        } else {
            log.debug("No Authorization header present");
        }
        filterChain.doFilter(request, response);
    }
}
