package com.ratelimiter.kafka.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * Base domain event — all Kafka messages inherit from this.
 * Carries correlation + causation IDs for distributed tracing.
 * Pattern: Domain Event (Event-Driven Architecture)
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "eventType")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RateLimitDecisionEvent.class, name = "RATE_LIMIT_DECISION"),
    @JsonSubTypes.Type(value = PolicyChangedEvent.class,     name = "POLICY_CHANGED"),
    @JsonSubTypes.Type(value = AlertTriggeredEvent.class,    name = "ALERT_TRIGGERED"),
    @JsonSubTypes.Type(value = AuditEvent.class,             name = "AUDIT"),
    @JsonSubTypes.Type(value = MetricsSnapshotEvent.class,   name = "METRICS_SNAPSHOT"),
    @JsonSubTypes.Type(value = BucketResetEvent.class,       name = "BUCKET_RESET"),
})
public abstract class BaseEvent {

    /** Unique ID for this event instance */
    private String eventId;

    /** Logical event type name */
    private String eventType;

    /** When this event occurred */
    private Instant occurredAt;

    /** Service instance that generated the event */
    private String sourceInstanceId;

    /** Correlation ID for request tracing (ties events from same request) */
    private String correlationId;

    /** ID of the event that caused this one (event chain) */
    private String causationId;

    /** Monotonically increasing version for schema evolution */
    private int schemaVersion;

    public static String newEventId() {
        return UUID.randomUUID().toString();
    }
}