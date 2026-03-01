package com.ratelimiter.pattern.observer;

import com.ratelimiter.kafka.event.BaseEvent;
import com.ratelimiter.kafka.event.PolicyChangedEvent;
import com.ratelimiter.kafka.event.RateLimitDecisionEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PATTERN: Observer (concrete observer)
 *
 * Updates Micrometer metrics from domain events.
 * Completely decoupled from the rate limiting core.
 * Priority 20 — runs after Kafka publish.
 */
@Slf4j
@Component
public class MetricsObserver implements EventObserver {

    private final MeterRegistry registry;
    private final Counter totalRequestsCounter;
    private final Counter allowedRequestsCounter;
    private final Counter blockedRequestsCounter;

    // Per-user counters for granular observability
    private final ConcurrentHashMap<String, AtomicLong> userRequestCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> userBlockedCounts  = new ConcurrentHashMap<>();

    public MetricsObserver(MeterRegistry registry) {
        this.registry = registry;
        this.totalRequestsCounter   = Counter.builder("ratelimiter.requests.total")
                .description("Total rate limit requests").register(registry);
        this.allowedRequestsCounter = Counter.builder("ratelimiter.requests.allowed")
                .description("Allowed requests").register(registry);
        this.blockedRequestsCounter = Counter.builder("ratelimiter.requests.blocked")
                .description("Blocked requests").register(registry);
    }

    @Override
    public boolean supports(Class<? extends BaseEvent> eventType) {
        return RateLimitDecisionEvent.class.isAssignableFrom(eventType)
                || PolicyChangedEvent.class.isAssignableFrom(eventType);
    }

    @Override
    public int order() { return 20; }

    @Override
    public void onEvent(BaseEvent event) {
        if (event instanceof RateLimitDecisionEvent e) {
            recordDecision(e);
        } else if (event instanceof PolicyChangedEvent e) {
            registry.counter("ratelimiter.policy.changes",
                    "type", e.getChangeType().name(),
                    "tier", e.getNewPolicy() != null ? e.getNewPolicy().getTier() : "UNKNOWN"
            ).increment();
        }
    }

    private void recordDecision(RateLimitDecisionEvent e) {
        totalRequestsCounter.increment();

        String outcome = e.isAllowed() ? "allowed" : "blocked";

        if (e.isAllowed()) {
            allowedRequestsCounter.increment();
        } else {
            blockedRequestsCounter.increment();
        }

        // Tagged counters for per-tier, per-outcome breakdown
        registry.counter("ratelimiter.decisions",
                "outcome", outcome,
                "tier", e.getTier() != null ? e.getTier() : "UNKNOWN",
                "userId", e.getUserId()
        ).increment();

        // Latency histogram
        if (e.getProcessingLatencyMs() > 0) {
            Timer.builder("ratelimiter.processing.latency")
                    .tag("outcome", outcome)
                    .register(registry)
                    .record(Duration.ofMillis(e.getProcessingLatencyMs()));
        }

        // Token utilization gauge
        registry.gauge("ratelimiter.bucket.utilization",
                java.util.List.of(
                    io.micrometer.core.instrument.Tag.of("userId", e.getUserId())
                ),
                e.getUtilizationPercent());

        // Per-user tracking
        userRequestCounts.computeIfAbsent(e.getUserId(), k -> new AtomicLong(0)).incrementAndGet();
        if (!e.isAllowed()) {
            userBlockedCounts.computeIfAbsent(e.getUserId(), k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    public long getUserRequestCount(String userId) {
        return userRequestCounts.getOrDefault(userId, new AtomicLong(0)).get();
    }

    public long getUserBlockedCount(String userId) {
        return userBlockedCounts.getOrDefault(userId, new AtomicLong(0)).get();
    }

    public ConcurrentHashMap<String, AtomicLong> getAllUserCounts() {
        return userRequestCounts;
    }
}