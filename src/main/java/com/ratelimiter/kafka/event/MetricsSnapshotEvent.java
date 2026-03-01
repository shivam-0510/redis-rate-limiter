package com.ratelimiter.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/**
 * Periodic metrics snapshot event.
 * Published by each instance every N seconds.
 * Consumers aggregate across instances for global metrics.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class MetricsSnapshotEvent extends BaseEvent {

    private long totalRequests;
    private long allowedRequests;
    private long blockedRequests;
    private double blockRate;
    private int activeBuckets;
    private long windowSeconds;

    /** Per-user stats in this window */
    private Map<String, UserMetrics> perUserMetrics;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserMetrics {
        private String userId;
        private long requests;
        private long blocked;
        private double avgTokensRemaining;
    }
}