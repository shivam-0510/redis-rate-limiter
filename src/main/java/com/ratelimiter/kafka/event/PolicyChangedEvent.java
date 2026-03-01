package com.ratelimiter.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Fired whenever a user's rate limit policy is created, updated, or deleted.
 * Allows other service instances to invalidate their local policy cache.
 * Pattern: Event-Carried State Transfer
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class PolicyChangedEvent extends BaseEvent {

    public enum ChangeType { CREATED, UPDATED, DELETED, TIER_CHANGED }

    private String userId;
    private ChangeType changeType;
    private String changedBy;

    /** Full new policy state (Event-Carried State Transfer) */
    private PolicySnapshot newPolicy;

    /** Previous policy state for audit trail */
    private PolicySnapshot previousPolicy;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PolicySnapshot {
        private long capacity;
        private long refillRate;
        private long refillPeriodSeconds;
        private String tier;
        private boolean active;
    }
}