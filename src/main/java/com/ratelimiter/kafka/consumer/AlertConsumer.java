package com.ratelimiter.kafka.consumer;

import com.ratelimiter.kafka.event.AlertTriggeredEvent;
import com.ratelimiter.kafka.event.BaseEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumes rate-limit-alerts topic.
 * In production this would fan out to:
 * - PagerDuty / OpsGenie
 * - Slack webhook
 * - Email notification
 * - Internal incident management
 */
@Slf4j
@Component
public class AlertConsumer {

    // In-memory store for recent alerts (in production: persist to DB)
    private final List<AlertTriggeredEvent> recentAlerts =
            Collections.synchronizedList(new ArrayList<>());

    // Per-type alert counts
    private final ConcurrentHashMap<String, Long> alertCounts = new ConcurrentHashMap<>();

    @KafkaListener(
            topics = "${kafka.topics.rate-limit-alerts:rate-limit-alerts}",
            groupId = "rate-limiter-alert-handler",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, BaseEvent> record, Acknowledgment ack) {
        try {
            if (record.value() instanceof AlertTriggeredEvent alert) {
                processAlert(alert);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Error processing alert: {}", e.getMessage(), e);
            throw e;
        }
    }

    private void processAlert(AlertTriggeredEvent alert) {
        log.warn("🚨 ALERT [{}] {} — user={}: {}",
                alert.getSeverity(), alert.getAlertType(),
                alert.getUserId(), alert.getDescription());

        // Store for dashboard display
        recentAlerts.add(0, alert);
        if (recentAlerts.size() > 100) recentAlerts.remove(recentAlerts.size() - 1);

        // Count by type
        alertCounts.merge(alert.getAlertType().name(), 1L, Long::sum);

        // In production: route based on severity
        switch (alert.getSeverity()) {
            case CRITICAL -> dispatchCritical(alert);
            case HIGH     -> dispatchHigh(alert);
            default       -> log.info("Non-critical alert recorded: {}", alert.getTitle());
        }
    }

    private void dispatchCritical(AlertTriggeredEvent alert) {
        // TODO: PagerDuty / OpsGenie integration
        log.error("CRITICAL ALERT — would page on-call: {} for user={}",
                alert.getTitle(), alert.getUserId());
    }

    private void dispatchHigh(AlertTriggeredEvent alert) {
        // TODO: Slack webhook
        log.warn("HIGH ALERT — would notify Slack: {} for user={}",
                alert.getTitle(), alert.getUserId());
    }

    public List<AlertTriggeredEvent> getRecentAlerts() {
        return List.copyOf(recentAlerts);
    }

    public Map<String, Long> getAlertCounts() {
        return Map.copyOf(alertCounts);
    }
}