package com.ratelimiter.kafka.consumer;

import com.ratelimiter.kafka.event.BaseEvent;
import com.ratelimiter.kafka.event.MetricsSnapshotEvent;
import com.ratelimiter.kafka.event.RateLimitDecisionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Consumes rate-limit-events topic.
 * Aggregates metrics across all service instances for a global view.
 * This is the cross-instance metrics aggregation layer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitEventConsumer {

    // Distributed aggregated counters — built from Kafka events across all instances
    private final AtomicLong globalTotal    = new AtomicLong(0);
    private final AtomicLong globalAllowed  = new AtomicLong(0);
    private final AtomicLong globalBlocked  = new AtomicLong(0);

    // Per-user aggregated stats
    private final ConcurrentHashMap<String, UserStats> userStats = new ConcurrentHashMap<>();

    // Per-instance stats (for multi-instance visibility)
    private final ConcurrentHashMap<String, InstanceStats> instanceStats = new ConcurrentHashMap<>();

    @KafkaListener(
            topics = "${kafka.topics.rate-limit-events:rate-limit-events}",
            groupId = "rate-limiter-metrics-aggregator",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(
            ConsumerRecord<String, BaseEvent> record,
            Acknowledgment ack,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        try {
            BaseEvent event = record.value();

            if (event instanceof RateLimitDecisionEvent e) {
                processDecision(e);
            } else if (event instanceof MetricsSnapshotEvent e) {
                processMetricsSnapshot(e);
            }

            ack.acknowledge();
            log.debug("Consumed event={} partition={} offset={}", event.getEventType(), partition, offset);

        } catch (Exception ex) {
            log.error("Error consuming record at partition={} offset={}: {}", partition, offset, ex.getMessage(), ex);
            // Don't ack — let error handler retry / DLT
            throw ex;
        }
    }

    private void processDecision(RateLimitDecisionEvent e) {
        globalTotal.incrementAndGet();
        if (e.isAllowed()) globalAllowed.incrementAndGet();
        else globalBlocked.incrementAndGet();

        // Per-user stats
        userStats.computeIfAbsent(e.getUserId(), k -> new UserStats(e.getUserId()))
                .record(e.isAllowed(), e.getTokensRemaining(), e.getUtilizationPercent());

        // Per-instance stats (visibility into distributed system)
        if (e.getSourceInstanceId() != null) {
            instanceStats.computeIfAbsent(e.getSourceInstanceId(), InstanceStats::new)
                    .increment(e.isAllowed());
        }
    }

    private void processMetricsSnapshot(MetricsSnapshotEvent e) {
        // Merge snapshot from a specific instance (for multi-instance deployments)
        log.debug("Received metrics snapshot from instance={} total={}",
                e.getSourceInstanceId(), e.getTotalRequests());
    }

    // ─── Accessors ────────────────────────────────────────

    public Map<String, Object> getAggregatedStats() {
        long total = globalTotal.get();
        long blocked = globalBlocked.get();
        return Map.of(
                "globalTotal",   total,
                "globalAllowed", globalAllowed.get(),
                "globalBlocked", blocked,
                "globalBlockRate", total > 0 ? Math.round((double) blocked / total * 10000.0) / 100.0 : 0.0,
                "trackedUsers",  userStats.size(),
                "activeInstances", instanceStats.size()
        );
    }

    public UserStats getUserStats(String userId) {
        return userStats.getOrDefault(userId, new UserStats(userId));
    }

    public Map<String, InstanceStats> getInstanceStats() {
        return Map.copyOf(instanceStats);
    }

    // ─── Inner DTOs ───────────────────────────────────────

    @lombok.Data
    public static class UserStats {
        private final String userId;
        private final AtomicLong requests = new AtomicLong(0);
        private final AtomicLong blocked  = new AtomicLong(0);
        private volatile double lastUtilization;
        private volatile long lastTokensRemaining;

        public UserStats(String userId) { this.userId = userId; }

        public void record(boolean allowed, long tokens, double utilization) {
            requests.incrementAndGet();
            if (!allowed) blocked.incrementAndGet();
            lastTokensRemaining = tokens;
            lastUtilization = utilization;
        }

        public double getBlockRate() {
            long r = requests.get();
            return r > 0 ? (double) blocked.get() / r * 100 : 0;
        }
    }

    @lombok.Data
    public static class InstanceStats {
        private final String instanceId;
        private final AtomicLong total   = new AtomicLong(0);
        private final AtomicLong blocked = new AtomicLong(0);

        public InstanceStats(String instanceId) { this.instanceId = instanceId; }

        public void increment(boolean allowed) {
            total.incrementAndGet();
            if (!allowed) blocked.incrementAndGet();
        }
    }
}