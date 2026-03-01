package com.ratelimiter.pattern.decorator;

import com.ratelimiter.kafka.event.BaseEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * PATTERN: Decorator
 *
 * Wraps the raw KafkaTemplate with:
 * 1. Circuit Breaker — opens after repeated Kafka failures, preventing cascade
 * 2. Retry — exponential backoff before escalating to DLT
 * 3. Logging + Metrics — transparent to callers
 *
 * Callers use ResilientKafkaProducer instead of KafkaTemplate directly,
 * gaining resilience without any change to event publishing code.
 */
@Slf4j
@Component
public class ResilientKafkaProducer {

    private final KafkaTemplate<String, BaseEvent> kafkaTemplate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public ResilientKafkaProducer(
            KafkaTemplate<String, BaseEvent> kafkaTemplate,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry) {

        this.kafkaTemplate   = kafkaTemplate;
        this.circuitBreaker  = circuitBreakerRegistry.circuitBreaker("kafka-producer");
        this.retry           = retryRegistry.retry("kafka-retry");

        // Log circuit breaker state transitions
        this.circuitBreaker.getEventPublisher()
                .onStateTransition(e -> log.warn("Kafka CircuitBreaker: {} → {}",
                        e.getStateTransition().getFromState(),
                        e.getStateTransition().getToState()));
    }

    /**
     * Decorated send: Circuit Breaker wraps Retry wraps actual send.
     * If the circuit is open, fails fast without attempting to contact Kafka.
     */
    public CompletableFuture<SendResult<String, BaseEvent>> send(
            String topic, String key, BaseEvent event) {

        try {
            return CircuitBreaker.decorateSupplier(circuitBreaker,
                    Retry.decorateSupplier(retry,
                            () -> doSend(topic, key, event)
                    )
            ).get();
        } catch (Exception e) {
            log.error("All retries exhausted / circuit open for topic={} key={} event={}: {}",
                    topic, key, event.getEventType(), e.getMessage());
            // Return completed-exceptionally so callers can handle gracefully
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<SendResult<String, BaseEvent>> doSend(
            String topic, String key, BaseEvent event) {
        log.debug("Sending event={} to topic={} key={}", event.getEventType(), topic, key);
        return kafkaTemplate.send(topic, key, event);
    }

    public CircuitBreaker.State getCircuitBreakerState() {
        return circuitBreaker.getState();
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }
}