package com.busapp.gatewayservice.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Reactive Redis client used by the Gateway to check the access-token
 * blacklist written by user-service on logout.
 *
 * Key format: blacklist:{accessToken}
 */
@Service
@RequiredArgsConstructor
public class RedisTokenService {

    private static final String BLACKLIST_PREFIX = "blacklist:";

    private final ReactiveStringRedisTemplate redis;

    public Mono<Boolean> isBlacklisted(String token) {
        return redis.hasKey(BLACKLIST_PREFIX + token);
    }
}
