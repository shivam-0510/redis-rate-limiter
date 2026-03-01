package com.ratelimiter.pattern.observer;

import com.ratelimiter.kafka.event.BaseEvent;
import com.ratelimiter.kafka.event.RateLimitDecisionEvent;
import com.ratelimiter.pattern.strategy.AlertingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * PATTERN: Observer (concrete observer)
 *
 * Bridges the Observer pipeline into the Strategy pattern.
 * Listens for RateLimitDecisionEvents and delegates to all AlertStrategies.
 * Priority 30 — runs after metrics.
 */
@Component
@RequiredArgsConstructor
public class AlertObserver implements EventObserver {

    private final AlertingService alertingService;

    @Override
    public boolean supports(Class<? extends BaseEvent> eventType) {
        return RateLimitDecisionEvent.class.isAssignableFrom(eventType);
    }

    @Override
    public int order() { return 30; }

    @Override
    public void onEvent(BaseEvent event) {
        alertingService.evaluate((RateLimitDecisionEvent) event);
    }
}