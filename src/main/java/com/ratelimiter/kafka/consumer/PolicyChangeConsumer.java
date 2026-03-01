package com.ratelimiter.kafka.consumer;

import com.ratelimiter.kafka.event.BaseEvent;
import com.ratelimiter.kafka.event.BucketResetEvent;
import com.ratelimiter.kafka.event.PolicyChangedEvent;
import com.ratelimiter.model.RateLimitPolicy;
import com.ratelimiter.service.PolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes policy-changes topic.
 *
 * Critical for multi-instance deployments:
 * When instance A updates a policy, this consumer on instances B and C
 * invalidates their local policy caches — ensuring consistency.
 *
 * Pattern: Event-Carried State Transfer
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyChangeConsumer {

    private final PolicyService policyService;

    @KafkaListener(
            topics = "${kafka.topics.policy-changes:policy-changes}",
            groupId = "rate-limiter-policy-sync",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, BaseEvent> record, Acknowledgment ack) {
        try {
            BaseEvent event = record.value();

            if (event instanceof PolicyChangedEvent e) {
                handlePolicyChange(e);
            } else if (event instanceof BucketResetEvent e) {
                handleBucketReset(e);
            }

            ack.acknowledge();
        } catch (Exception ex) {
            log.error("Error processing policy change: {}", ex.getMessage(), ex);
            throw ex;
        }
    }

    private void handlePolicyChange(PolicyChangedEvent event) {
        log.info("Policy change received: userId={} type={} from instance={}",
                event.getUserId(), event.getChangeType(), event.getSourceInstanceId());

        switch (event.getChangeType()) {
            case CREATED, UPDATED, TIER_CHANGED -> {
                // Apply the new policy state (Event-Carried State Transfer)
                if (event.getNewPolicy() != null) {
                    PolicyChangedEvent.PolicySnapshot snap = event.getNewPolicy();
                    RateLimitPolicy policy = RateLimitPolicy.builder()
                            .userId(event.getUserId())
                            .capacity(snap.getCapacity())
                            .refillRate(snap.getRefillRate())
                            .refillPeriodSeconds(snap.getRefillPeriodSeconds())
                            .tier(snap.getTier())
                            .active(snap.isActive())
                            .build();
                    policyService.updatePolicy(policy);
                    log.debug("Policy cache updated for user={}", event.getUserId());
                }
            }
            case DELETED -> {
                policyService.deletePolicy(event.getUserId());
                log.debug("Policy cache invalidated for user={}", event.getUserId());
            }
        }
    }

    private void handleBucketReset(BucketResetEvent event) {
        // Bucket resets are applied directly to Redis — no local state to clear
        log.info("Bucket reset event received for user={} by={}",
                event.getUserId(), event.getResetBy());
    }
}