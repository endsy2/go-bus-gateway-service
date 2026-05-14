package com.busapp.gatewayservice.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * Key resolvers for the Resilience4j-backed rate limiter.
 *
 *  - ipKeyResolver:   resolves by client IP  → used for public/auth routes
 *  - userKeyResolver: resolves by X-User-Id header, falls back to IP
 *                     → used for all authenticated routes
 *
 * Rate limiter limits are configured in {@link com.busapp.gatewayservice.config.Resilience4jConfig}.
 */
@Configuration
public class RateLimiterConfig {

    // ── Key Resolvers ──────────────────────────────────────────────────────────

    /**
     * Primary resolver – uses X-User-Id injected by JwtAuthenticationFilter.
     * Falls back to client IP for unauthenticated traffic.
     */
    @Bean
    @Primary
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                return Mono.just("user:" + userId);
            }
            return Mono.just("ip:" + resolveIp(exchange.getRequest().getRemoteAddress()));
        };
    }

    /**
     * IP-only resolver – used for auth routes where no JWT is expected.
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
                "ip:" + resolveIp(exchange.getRequest().getRemoteAddress())
        );
    }

    private String resolveIp(InetSocketAddress remote) {
        return Optional.ofNullable(remote)
                .map(InetSocketAddress::getAddress)
                .map(InetAddress::getHostAddress)
                .orElse("unknown");
    }
}
