package com.ratelimiter.pattern.observer;

import com.ratelimiter.kafka.event.BaseEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * PATTERN: Observer — Subject/Publisher
 *
 * Central event bus that distributes domain events to all registered observers.
 * The RateLimiterService calls publish() and knows nothing about
 * Kafka, alerting, metrics, or auditing — pure SRP.
 */
@Slf4j
@Component
public class EventPublisher {

    private final List<EventObserver> observers;

    public EventPublisher(List<EventObserver> observers) {
        // Sort by priority on startup
        this.observers = observers.stream()
                .sorted(Comparator.comparingInt(EventObserver::order))
                .toList();

        log.info("EventPublisher initialized with {} observers: {}",
                observers.size(),
                observers.stream().map(o -> o.getClass().getSimpleName()).toList());
    }

    /**
     * Notify all interested observers about a domain event.
     * Each observer runs independently — failure in one doesn't block others.
     */
    public void publish(BaseEvent event) {
        observers.stream()
                .filter(o -> o.supports(event.getClass()))
                .forEach(observer -> {
                    try {
                        observer.onEvent(event);
                    } catch (Exception e) {
                        log.error("Observer {} failed to process event {}: {}",
                                observer.getClass().getSimpleName(),
                                event.getEventType(),
                                e.getMessage(), e);
                    }
                });
    }
}