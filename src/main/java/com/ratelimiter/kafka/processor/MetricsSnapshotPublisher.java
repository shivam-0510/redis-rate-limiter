package com.ratelimiter.kafka.processor;

import com.ratelimiter.kafka.event.BaseEvent;
import com.ratelimiter.kafka.event.MetricsSnapshotEvent;
import com.ratelimiter.pattern.factory.EventFactory;
import com.ratelimiter.pattern.observer.EventPublisher;
import com.ratelimiter.pattern.observer.MetricsObserver;
import com.ratelimiter.service.RateLimiterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Periodic metrics aggregation publisher.
 *
 * PATTERN: Saga (simplified single-service saga step)
 * Every 30 seconds, collects this instance's stats and publishes
 * a MetricsSnapshotEvent to Kafka so all instances can build
 * a global view by consuming each other's snapshots.
 *
 * In a full multi-instance deployment this implements the
 * "tell others about your state" step of a distributed saga.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsSnapshotPublisher {

    private final RateLimiterService rateLimiterService;
    private final MetricsObserver metricsObserver;
    private final EventPublisher eventPublisher;

    @Scheduled(fixedDelayString = "${app.metrics.snapshot-interval-ms:30000}")
    public void publishSnapshot() {
        try {
            Map<String, Object> stats = rateLimiterService.getSystemStats();

            // Build per-user metrics map
            Map<String, MetricsSnapshotEvent.UserMetrics> perUser = new HashMap<>();
            metricsObserver.getAllUserCounts().forEach((userId, count) -> {
                long blocked = metricsObserver.getUserBlockedCount(userId);
                perUser.put(userId, new MetricsSnapshotEvent.UserMetrics(
                        userId, count.get(), blocked, 0.0));
            });

            MetricsSnapshotEvent event = MetricsSnapshotEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType("METRICS_SNAPSHOT")
                    .occurredAt(Instant.now())
                    .sourceInstanceId((String) stats.get("instanceId"))
                    .schemaVersion(1)
                    .totalRequests((Long) stats.get("totalRequests"))
                    .allowedRequests((Long) stats.get("allowedRequests"))
                    .blockedRequests((Long) stats.get("blockedRequests"))
                    .blockRate(((Number) stats.get("blockRate")).doubleValue())
                    .activeBuckets((Integer) stats.get("activeBuckets"))
                    .windowSeconds(30)
                    .perUserMetrics(perUser)
                    .build();

            eventPublisher.publish(event);
            log.debug("Published metrics snapshot: total={} blocked={} users={}",
                    event.getTotalRequests(), event.getBlockedRequests(), perUser.size());

        } catch (Exception e) {
            log.error("Failed to publish metrics snapshot: {}", e.getMessage());
        }
    }
}