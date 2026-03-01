package com.ratelimiter.pattern.strategy;

import com.ratelimiter.kafka.event.AlertTriggeredEvent;
import com.ratelimiter.kafka.event.RateLimitDecisionEvent;

import java.util.Optional;

/**
 * PATTERN: Strategy
 *
 * Each AlertStrategy encapsulates a distinct alert detection algorithm.
 * New alert rules can be added without changing the alerting service —
 * just register a new bean implementing this interface.
 *
 * Context: AlertingService selects and invokes all applicable strategies.
 */
public interface AlertStrategy {

    /**
     * Evaluate the incoming event and return an alert if the rule fires.
     * @param event the decision event to evaluate
     * @return Optional alert, or empty if rule didn't fire
     */
    Optional<AlertTriggeredEvent> evaluate(RateLimitDecisionEvent event);

    /**
     * Unique name for this strategy (used in logging/metrics).
     */
    String strategyName();

    /**
     * Whether this strategy is currently enabled.
     */
    default boolean isEnabled() { return true; }
}