package com.ratelimiter.pattern.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.kafka.event.BaseEvent;
import com.ratelimiter.pattern.decorator.ResilientKafkaProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * PATTERN: Transactional Outbox
 *
 * Problem: After updating Redis (rate limit state), publishing to Kafka
 * could fail — leaving the system in an inconsistent state.
 *
 * Solution: Write events to a Redis outbox atomically with the state change.
 * A background relay picks up outbox entries and publishes them to Kafka.
 * Only removes from outbox after confirmed Kafka delivery.
 *
 * This guarantees at-least-once delivery to Kafka.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final RedisTemplate<String, String> redisTemplate;
    private final ResilientKafkaProducer kafkaProducer;
    private final ObjectMapper objectMapper;

    private static final String OUTBOX_KEY = "rl:outbox:pending";
    private static final String PROCESSING_KEY = "rl:outbox:processing";

    @Value("${app.outbox.max-batch-size:50}")
    private int maxBatchSize;

    @Value("${kafka.topics.rate-limit-events:rate-limit-events}")
    private String defaultTopic;

    /**
     * Write an event to the Redis outbox.
     * Called within the same Redis operation as the state change.
     */
    public void enqueue(String topic, String partitionKey, BaseEvent event) {
        try {
            OutboxEntry entry = new OutboxEntry(topic, partitionKey, event,
                    event.getClass().getName(), System.currentTimeMillis());
            String json = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForList().rightPush(OUTBOX_KEY, json);
        } catch (Exception e) {
            log.error("Failed to enqueue outbox entry for event={}: {}",
                    event.getEventType(), e.getMessage());
        }
    }

    /**
     * Relay: runs every second, picks up batch from outbox, publishes to Kafka.
     * On success: removes from outbox.
     * On failure: moves back to pending (retry on next cycle).
     */
    @Scheduled(fixedDelayString = "${app.outbox.flush-interval-ms:1000}")
    public void relay() {
        List<String> batch = new ArrayList<>();

        // Atomically move batch from pending → processing
        for (int i = 0; i < maxBatchSize; i++) {
            String entry = redisTemplate.opsForList().leftPop(OUTBOX_KEY);
            if (entry == null) break;
            batch.add(entry);
            redisTemplate.opsForList().rightPush(PROCESSING_KEY, entry);
        }

        if (batch.isEmpty()) return;

        log.debug("Outbox relay: processing {} entries", batch.size());
        int published = 0;
        int failed = 0;

        for (String json : batch) {
            try {
                OutboxEntry entry = objectMapper.readValue(json, OutboxEntry.class);
                kafkaProducer.send(entry.getTopic(), entry.getPartitionKey(), entry.getEvent())
                        .whenComplete((result, ex) -> {
                            if (ex == null) {
                                // Successfully delivered — remove from processing queue
                                redisTemplate.opsForList().remove(PROCESSING_KEY, 1, json);
                                log.debug("Outbox entry delivered: event={}", entry.getEvent().getEventType());
                            } else {
                                // Failed — move back to pending for retry
                                redisTemplate.opsForList().remove(PROCESSING_KEY, 1, json);
                                redisTemplate.opsForList().rightPush(OUTBOX_KEY, json);
                                log.warn("Outbox entry failed, requeued: {}", ex.getMessage());
                            }
                        });
                published++;
            } catch (Exception e) {
                log.error("Failed to process outbox entry: {}", e.getMessage());
                redisTemplate.opsForList().remove(PROCESSING_KEY, 1, json);
                redisTemplate.opsForList().rightPush(OUTBOX_KEY, json); // requeue
                failed++;
            }
        }

        if (published > 0 || failed > 0) {
            log.debug("Outbox relay complete: published={}, failed={}", published, failed);
        }
    }

    public long pendingCount() {
        Long size = redisTemplate.opsForList().size(OUTBOX_KEY);
        return size != null ? size : 0;
    }

    // ─── Outbox Entry DTO ─────────────────────────────────

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OutboxEntry {
        private String topic;
        private String partitionKey;
        private BaseEvent event;
        private String eventClassName;
        private long enqueuedAt;
    }
}