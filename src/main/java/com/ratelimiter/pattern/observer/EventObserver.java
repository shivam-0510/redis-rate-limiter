package com.ratelimiter.pattern.observer;

import com.ratelimiter.kafka.event.BaseEvent;

/**
 * PATTERN: Observer
 *
 * EventPublisher is the Subject. EventObserver implementations
 * are the Observers — each reacts independently to rate limit decisions.
 *
 * This decouples the core rate limiting logic from side-effects
 * like Kafka publishing, alerting, metrics, and auditing.
 */
public interface EventObserver {

    /**
     * Called whenever a domain event is published.
     * @param event the event to handle
     */
    void onEvent(BaseEvent event);

    /**
     * Whether this observer is interested in the given event type.
     * Allows observers to selectively filter events.
     */
    boolean supports(Class<? extends BaseEvent> eventType);

    /**
     * Execution priority — lower number runs first.
     */
    default int order() { return 100; }
}