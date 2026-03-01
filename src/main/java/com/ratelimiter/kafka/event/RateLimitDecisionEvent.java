package com.ratelimiter.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Fired for every rate limit decision — allowed or blocked.
 * High-volume event: goes to rate-limit-events topic.
 * Partitioned by userId for ordered per-user processing.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RateLimitDecisionEvent extends BaseEvent {

    private String userId;
    private boolean allowed;
    private long tokensRemaining;
    private long capacity;
    private long refillRate;
    private long retryAfterSeconds;
    private int tokensConsumed;

    /** Request-level metadata */
    private String clientIp;
    private String endpoint;
    private String userAgent;

    /** Bucket health at decision time */
    private double utilizationPercent;
    private String tier;

    /** Processing latency in ms */
    private long processingLatencyMs;
}