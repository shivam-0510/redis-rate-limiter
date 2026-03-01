package com.ratelimiter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitPolicy {
    private String userId;
    private long capacity;           // max tokens
    private long refillRate;         // tokens per refill period
    private long refillPeriodSeconds;
    private String tier;             // FREE, BASIC, PREMIUM, ENTERPRISE
    private boolean active;

    public static RateLimitPolicy defaultPolicy(String userId) {
        return RateLimitPolicy.builder()
                .userId(userId)
                .capacity(100)
                .refillRate(10)
                .refillPeriodSeconds(1)
                .tier("FREE")
                .active(true)
                .build();
    }

    public static RateLimitPolicy forTier(String userId, String tier) {
        return switch (tier.toUpperCase()) {
            case "BASIC" -> RateLimitPolicy.builder()
                    .userId(userId).capacity(500).refillRate(50)
                    .refillPeriodSeconds(1).tier("BASIC").active(true).build();
            case "PREMIUM" -> RateLimitPolicy.builder()
                    .userId(userId).capacity(2000).refillRate(200)
                    .refillPeriodSeconds(1).tier("PREMIUM").active(true).build();
            case "ENTERPRISE" -> RateLimitPolicy.builder()
                    .userId(userId).capacity(10000).refillRate(1000)
                    .refillPeriodSeconds(1).tier("ENTERPRISE").active(true).build();
            default -> defaultPolicy(userId);
        };
    }
}