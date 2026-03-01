package com.ratelimiter.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.Map;

/**
 * Fired when an alert rule threshold is breached.
 * Downstream alert consumers can notify via PagerDuty, Slack, email, etc.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AlertTriggeredEvent extends BaseEvent {

    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }
    public enum AlertType {
        HIGH_BLOCK_RATE,
        SUSTAINED_ABUSE,
        BUCKET_EXHAUSTED,
        SUSPICIOUS_TRAFFIC_SPIKE,
        POLICY_VIOLATION,
        SYSTEM_DEGRADED
    }

    private String userId;
    private AlertType alertType;
    private Severity severity;
    private String title;
    private String description;

    /** Raw metric values that triggered the alert */
    private Map<String, Object> context;

    /** Suggested remediation action */
    private String suggestedAction;

    /** Whether this is a new alert or a repeat */
    private boolean repeated;
    private int repeatCount;
}