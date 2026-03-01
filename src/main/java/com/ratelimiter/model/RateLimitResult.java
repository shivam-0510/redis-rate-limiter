package com.ratelimiter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitResult {
    private boolean allowed;
    private long tokensRemaining;
    private long capacity;
    private long refillRate;
    private long retryAfterSeconds;
    private String userId;
    private long requestTimestamp;
    private String instanceId;
}