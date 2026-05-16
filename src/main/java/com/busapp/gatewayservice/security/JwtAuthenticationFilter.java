package com.busapp.gatewayservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

/**
 * Runs before every route.
 *
 * Validation steps:
 *  1. Skip public paths (/api/auth/**, /actuator/**)
 *  2. Extract and parse Bearer token — reject 401 on missing/malformed
 *  3. Verify RS256 signature with RSA public key
 *  4. Check token type == "access"
 *  5. Check Redis blacklist (token revoked by logout)
 *  6. Forward with X-User-Id / X-User-Email / X-User-Name headers
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final RSAPublicKey      rsaPublicKey;
    private final RedisTokenService redisTokenService;

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh",
            "/actuator",
            "/health",
            "/",
            // WebSocket endpoints - all SockJS paths
            "/bus-service/ws/",
            "/booking-service/ws/",
            "/admin/ws/"
    );

    @Override
    public int getOrder() {
        return -100; // Run before routing
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Skip public paths
        if (PUBLIC_PATHS.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        // Extract token
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header.");
        }
        String token = authHeader.substring(7);

        // Parse + verify signature
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(rsaPublicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.info("Access token expired for path {}: {}", exchange.getRequest().getURI().getPath(), e.getMessage());
            return rejectWithBody(exchange, HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED",
                    "Access token has expired. Please use POST /api/auth/refresh to obtain a new token.");
        } catch (JwtException e) {
            log.info("JWT validation failed: {}", e.getMessage());
            return rejectWithBody(exchange, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                    "Invalid token. Please log in again.");
        }

        // Enforce access token type

        if (!"access".equals(claims.get("type"))) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Token is not an access token.");
        }

        // Check Redis blacklist
        return redisTokenService.isBlacklisted(token)
                .flatMap(blacklisted -> {
                    if (Boolean.TRUE.equals(blacklisted)) {
                        return reject(exchange, HttpStatus.UNAUTHORIZED, "Token has been revoked.");
                    }

                    // Flatten roles and permissions lists from JWT claims
                    @SuppressWarnings("unchecked")
                    List<String> roles = (List<String>) claims.getOrDefault("roles", List.of());
                    @SuppressWarnings("unchecked")
                    List<String> permissions = (List<String>) claims.getOrDefault("permissions", List.of());

                    String rolesHeader       = String.join(",", roles);
                    String permissionsHeader = String.join(",", permissions);

                    // ServerHttpRequestDecorator is the only reliable way to add headers
                    // in Spring WebFlux 6.1.x. The Builder API (.header() / .headers())
                    // both operate on the original ReadOnlyHttpHeaders reference and throw
                    // UnsupportedOperationException. The decorator overrides getHeaders()
                    // with a fresh mutable copy that already contains all original headers.
                    HttpHeaders newHeaders = new HttpHeaders();
                    newHeaders.putAll(exchange.getRequest().getHeaders());
                    newHeaders.set("X-User-Id",          claims.getSubject());
                    newHeaders.set("X-User-Email",       claims.get("email", String.class));
                    newHeaders.set("X-User-Name",        claims.get("userName", String.class));
                    newHeaders.set("X-User-Roles",       rolesHeader);
                    newHeaders.set("X-User-Permissions", permissionsHeader);

                    ServerHttpRequestDecorator mutatedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override
                        public HttpHeaders getHeaders() {
                            return HttpHeaders.readOnlyHttpHeaders(newHeaders);
                        }
                    };

                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                });
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String reason) {
        log.warn("Gateway blocked request to {}: {}", exchange.getRequest().getURI().getPath(), reason);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().add("X-Auth-Error", reason);
        return exchange.getResponse().setComplete();
    }

    private Mono<Void> rejectWithBody(ServerWebExchange exchange, HttpStatus status,
                                      String errorCode, String message) {
        log.warn("Gateway blocked request to {}: {} — {}",
                exchange.getRequest().getURI().getPath(), errorCode, message);
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().add("X-Auth-Error", errorCode);
        String body = String.format(
                "{\"status\":%d,\"error\":\"%s\",\"message\":\"%s\"}",
                status.value(), errorCode, message);
        DataBuffer buffer = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
