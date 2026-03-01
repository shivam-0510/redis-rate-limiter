package com.ratelimiter.pattern.observer;

import com.ratelimiter.kafka.event.*;
import com.ratelimiter.kafka.event.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * PATTERN: Observer (concrete observer)
 *
 * Routes domain events to the appropriate Kafka topic.
 * Runs at priority 10 — earliest of all observers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaEventObserver implements EventObserver {

    private final KafkaTemplate<String, BaseEvent> kafkaTemplate;

    @Value("${kafka.topics.rate-limit-events:rate-limit-events}")
    private String rateLimitEventsTopic;

    @Value("${kafka.topics.rate-limit-alerts:rate-limit-alerts}")
    private String alertsTopic;

    @Value("${kafka.topics.audit-log:audit-log}")
    private String auditLogTopic;

    @Value("${kafka.topics.policy-changes:policy-changes}")
    private String policyChangesTopic;

    @Value("${kafka.topics.metrics-aggregation:metrics-aggregation}")
    private String metricsTopic;

    @Override
    public boolean supports(Class<? extends BaseEvent> eventType) {
        return true; // handles all events
    }

    @Override
    public int order() { return 10; }

    @Override
    public void onEvent(BaseEvent event) {
        String topic = resolveTopic(event);
        String partitionKey = resolvePartitionKey(event);

        CompletableFuture<SendResult<String, BaseEvent>> future =
                kafkaTemplate.send(topic, partitionKey, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish {} to topic={} key={}: {}",
                        event.getEventType(), topic, partitionKey, ex.getMessage());
            } else {
                log.debug("Published {} to {}[{}]@{}",
                        event.getEventType(), topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }

    private String resolveTopic(BaseEvent event) {
        return switch (event) {
            case RateLimitDecisionEvent e -> rateLimitEventsTopic;
            case AlertTriggeredEvent e    -> alertsTopic;
            case AuditEvent e            -> auditLogTopic;
            case PolicyChangedEvent e    -> policyChangesTopic;
            case MetricsSnapshotEvent e  -> metricsTopic;
            case BucketResetEvent e      -> policyChangesTopic;
            default                      -> rateLimitEventsTopic;
        };
    }

    private String resolvePartitionKey(BaseEvent event) {
        // Partition by userId for ordered per-user event stream
        return switch (event) {
            case RateLimitDecisionEvent e -> e.getUserId();
            case PolicyChangedEvent e     -> e.getUserId();
            case AlertTriggeredEvent e    -> e.getUserId() != null ? e.getUserId() : "system";
            case AuditEvent e             -> e.getTargetUserId() != null ? e.getTargetUserId() : "system";
            case BucketResetEvent e       -> e.getUserId();
            default                       -> event.getSourceInstanceId();
        };
    }
}