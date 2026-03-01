package com.ratelimiter.pattern.factory;

import com.ratelimiter.kafka.event.*;
import com.ratelimiter.model.RateLimitPolicy;
import com.ratelimiter.model.RateLimitResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * PATTERN: Factory (Abstract Factory)
 *
 * Centralizes event construction — every event gets proper:
 * - eventId (UUID)
 * - occurredAt (Instant.now())
 * - sourceInstanceId
 * - schemaVersion
 * - correlationId
 *
 * Callers never manually build events — they call the factory.
 * Changing event shape only requires updating the factory.
 */
@Component
public class EventFactory {

    @Value("${app.instance-id:default}")
    private String instanceId;

    private static final int SCHEMA_VERSION = 1;

    // ─── Rate Limit Decision ──────────────────────────────

    public RateLimitDecisionEvent createDecisionEvent(
            RateLimitResult result,
            int tokensConsumed,
            String correlationId,
            String clientIp,
            String endpoint,
            long latencyMs) {

        double utilization = result.getCapacity() > 0
                ? ((double)(result.getCapacity() - result.getTokensRemaining()) / result.getCapacity()) * 100
                : 0;

        return RateLimitDecisionEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("RATE_LIMIT_DECISION")
                .occurredAt(Instant.now())
                .sourceInstanceId(instanceId)
                .correlationId(correlationId)
                .schemaVersion(SCHEMA_VERSION)
                // Payload
                .userId(result.getUserId())
                .allowed(result.isAllowed())
                .tokensRemaining(result.getTokensRemaining())
                .capacity(result.getCapacity())
                .refillRate(result.getRefillRate())
                .retryAfterSeconds(result.getRetryAfterSeconds())
                .tokensConsumed(tokensConsumed)
                .utilizationPercent(utilization)
                .processingLatencyMs(latencyMs)
                .clientIp(clientIp)
                .endpoint(endpoint)
                .build();
    }

    // ─── Policy Changed ───────────────────────────────────

    public PolicyChangedEvent createPolicyChangedEvent(
            RateLimitPolicy newPolicy,
            RateLimitPolicy previousPolicy,
            PolicyChangedEvent.ChangeType changeType,
            String changedBy) {

        return PolicyChangedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("POLICY_CHANGED")
                .occurredAt(Instant.now())
                .sourceInstanceId(instanceId)
                .schemaVersion(SCHEMA_VERSION)
                .userId(newPolicy.getUserId())
                .changeType(changeType)
                .changedBy(changedBy)
                .newPolicy(toSnapshot(newPolicy))
                .previousPolicy(previousPolicy != null ? toSnapshot(previousPolicy) : null)
                .build();
    }

    // ─── Audit ────────────────────────────────────────────

    public AuditEvent createAuditEvent(
            AuditEvent.Action action,
            String actorId,
            String targetUserId,
            boolean success) {

        return AuditEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("AUDIT")
                .occurredAt(Instant.now())
                .sourceInstanceId(instanceId)
                .schemaVersion(SCHEMA_VERSION)
                .action(action)
                .actorId(actorId)
                .targetUserId(targetUserId)
                .success(success)
                .build();
    }

    // ─── Bucket Reset ─────────────────────────────────────

    public BucketResetEvent createBucketResetEvent(
            String userId,
            String resetBy,
            String reason,
            long tokensBefore,
            long capacityAfter) {

        return BucketResetEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("BUCKET_RESET")
                .occurredAt(Instant.now())
                .sourceInstanceId(instanceId)
                .schemaVersion(SCHEMA_VERSION)
                .userId(userId)
                .resetBy(resetBy)
                .reason(reason)
                .tokensBeforeReset(tokensBefore)
                .capacityAfterReset(capacityAfter)
                .build();
    }

    // ─── Helpers ──────────────────────────────────────────

    private PolicyChangedEvent.PolicySnapshot toSnapshot(RateLimitPolicy policy) {
        return new PolicyChangedEvent.PolicySnapshot(
                policy.getCapacity(),
                policy.getRefillRate(),
                policy.getRefillPeriodSeconds(),
                policy.getTier(),
                policy.isActive()
        );
    }
}