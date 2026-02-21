package com.ratelimiter.redis_rate_limiter.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private final StringRedisTemplate redisTemplate;

    private final DefaultRedisScript<Long> redisScript;

    public boolean isAllowed(String userId) {

        Long result = redisTemplate.execute(
                redisScript,
                Collections.singletonList("rate_limit:" + userId),
                "5",
                "10",
                String.valueOf(System.currentTimeMillis() / 1000)
        );

        return result != null && result == 1;
    }
}
