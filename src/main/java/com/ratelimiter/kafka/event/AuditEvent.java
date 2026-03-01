package com.ratelimiter.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/**
 * Immutable audit record for all administrative actions.
 * Goes to audit-log topic with infinite retention.
 * Pattern: Audit Log / Event Sourcing
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AuditEvent extends BaseEvent {

    public enum Action {
        POLICY_CREATED, POLICY_UPDATED, POLICY_DELETED,
        BUCKET_RESET, TIER_CHANGED, SIMULATION_STARTED,
        ALERT_ACKNOWLEDGED, SYSTEM_CONFIG_CHANGED
    }

    private Action action;
    private String actorId;        // who performed the action
    private String targetUserId;   // who was affected
    private String targetResource;
    private Map<String, Object> before;
    private Map<String, Object> after;
    private String reason;
    private boolean success;
    private String errorMessage;
}