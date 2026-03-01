package com.ratelimiter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenBucket {
    private String userId;
    private long capacity;
    private long tokens;
    private long refillRate;        // tokens per period
    private long refillPeriodSeconds;
    private Instant lastRefillTime;
    private Instant createdAt;
}