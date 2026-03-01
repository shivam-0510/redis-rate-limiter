package com.ratelimiter.pattern.strategy;

import com.ratelimiter.kafka.event.AlertTriggeredEvent;
import com.ratelimiter.kafka.event.BaseEvent;
import com.ratelimiter.kafka.event.RateLimitDecisionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PATTERN: Strategy — three concrete alert strategies.
 *
 * 1. HighBlockRateStrategy    — fires when a user's block rate exceeds threshold
 * 2. SustainedAbuseStrategy   — fires when a user hits N blocks in a time window
 * 3. BucketExhaustedStrategy  — fires when a bucket reaches 0 tokens
 */
public class AlertStrategies {

    // ─── Strategy 1: High Block Rate ──────────────────────

    @Slf4j
    @Component
    public static class HighBlockRateStrategy implements AlertStrategy {

        @Value("${app.alerts.block-rate-threshold:80.0}")
        private double blockRateThreshold;

        // Track rolling window per user: [totalRequests, blockedRequests]
        private final ConcurrentHashMap<String, long[]> windowStats = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Long>   windowStart = new ConcurrentHashMap<>();
        private static final long WINDOW_MS = 60_000L;

        @Override
        public Optional<AlertTriggeredEvent> evaluate(RateLimitDecisionEvent event) {
            String userId = event.getUserId();
            long now = System.currentTimeMillis();

            // Reset window if expired
            long start = windowStart.computeIfAbsent(userId, k -> now);
            if (now - start > WINDOW_MS) {
                windowStats.put(userId, new long[]{0, 0});
                windowStart.put(userId, now);
            }

            long[] stats = windowStats.computeIfAbsent(userId, k -> new long[]{0, 0});
            stats[0]++; // total
            if (!event.isAllowed()) stats[1]++; // blocked

            double blockRate = stats[0] > 10 ? (double) stats[1] / stats[0] * 100 : 0;

            if (blockRate >= blockRateThreshold && stats[0] >= 10) {
                log.warn("HIGH_BLOCK_RATE alert: user={} blockRate={:.1f}%", userId, blockRate);
                return Optional.of(buildAlert(event, blockRate));
            }
            return Optional.empty();
        }

        private AlertTriggeredEvent buildAlert(RateLimitDecisionEvent event, double blockRate) {
            return AlertTriggeredEvent.builder()
                    .eventId(BaseEvent.newEventId())
                    .eventType("ALERT_TRIGGERED")
                    .occurredAt(Instant.now())
                    .userId(event.getUserId())
                    .alertType(AlertTriggeredEvent.AlertType.HIGH_BLOCK_RATE)
                    .severity(blockRate >= 95 ? AlertTriggeredEvent.Severity.CRITICAL : AlertTriggeredEvent.Severity.HIGH)
                    .title("High Block Rate Detected")
                    .description(String.format("User %s has %.1f%% block rate in last 60s", event.getUserId(), blockRate))
                    .suggestedAction("Review policy limits or investigate abusive client")
                    .context(Map.of("blockRate", blockRate, "tier", event.getTier()))
                    .build();
        }

        @Override
        public String strategyName() { return "HIGH_BLOCK_RATE"; }
    }

    // ─── Strategy 2: Sustained Abuse ──────────────────────

    @Slf4j
    @Component
    public static class SustainedAbuseStrategy implements AlertStrategy {

        @Value("${app.alerts.block-count-threshold:50}")
        private long blockCountThreshold;

        @Value("${app.alerts.block-count-window-seconds:60}")
        private long windowSeconds;

        private final ConcurrentHashMap<String, AtomicLong> blockCounts = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Long>       windowStart = new ConcurrentHashMap<>();

        @Override
        public Optional<AlertTriggeredEvent> evaluate(RateLimitDecisionEvent event) {
            if (event.isAllowed()) return Optional.empty();

            String userId = event.getUserId();
            long now = System.currentTimeMillis();

            long start = windowStart.computeIfAbsent(userId, k -> now);
            if (now - start > windowSeconds * 1000L) {
                blockCounts.put(userId, new AtomicLong(0));
                windowStart.put(userId, now);
            }

            long count = blockCounts.computeIfAbsent(userId, k -> new AtomicLong(0))
                                    .incrementAndGet();

            if (count == blockCountThreshold) {
                return Optional.of(AlertTriggeredEvent.builder()
                        .eventId(BaseEvent.newEventId())
                        .eventType("ALERT_TRIGGERED")
                        .occurredAt(Instant.now())
                        .userId(userId)
                        .alertType(AlertTriggeredEvent.AlertType.SUSTAINED_ABUSE)
                        .severity(AlertTriggeredEvent.Severity.HIGH)
                        .title("Sustained Rate Limit Abuse")
                        .description(String.format("User %s blocked %d times in %ds window", userId, count, windowSeconds))
                        .suggestedAction("Consider temporary IP ban or account suspension")
                        .context(Map.of("blockCount", count, "windowSeconds", windowSeconds))
                        .build());
            }
            return Optional.empty();
        }

        @Override
        public String strategyName() { return "SUSTAINED_ABUSE"; }
    }

    // ─── Strategy 3: Bucket Exhausted ─────────────────────

    @Slf4j
    @Component
    public static class BucketExhaustedStrategy implements AlertStrategy {

        private final ConcurrentHashMap<String, Long> lastExhaustedAlert = new ConcurrentHashMap<>();
        private static final long ALERT_COOLDOWN_MS = 30_000L; // 30s cooldown per user

        @Override
        public Optional<AlertTriggeredEvent> evaluate(RateLimitDecisionEvent event) {
            if (event.isAllowed() || event.getTokensRemaining() > 0) return Optional.empty();

            String userId = event.getUserId();
            long now = System.currentTimeMillis();
            Long lastAlert = lastExhaustedAlert.get(userId);

            if (lastAlert != null && now - lastAlert < ALERT_COOLDOWN_MS) {
                return Optional.empty(); // cooldown active
            }

            lastExhaustedAlert.put(userId, now);

            return Optional.of(AlertTriggeredEvent.builder()
                    .eventId(BaseEvent.newEventId())
                    .eventType("ALERT_TRIGGERED")
                    .occurredAt(Instant.now())
                    .userId(userId)
                    .alertType(AlertTriggeredEvent.AlertType.BUCKET_EXHAUSTED)
                    .severity(AlertTriggeredEvent.Severity.MEDIUM)
                    .title("Token Bucket Exhausted")
                    .description(String.format("User %s has 0 tokens remaining (capacity=%d)", userId, event.getCapacity()))
                    .suggestedAction("Monitor for continued pressure; consider tier upgrade")
                    .context(Map.of("capacity", event.getCapacity(), "tier", event.getTier()))
                    .build());
        }

        @Override
        public String strategyName() { return "BUCKET_EXHAUSTED"; }
    }
}