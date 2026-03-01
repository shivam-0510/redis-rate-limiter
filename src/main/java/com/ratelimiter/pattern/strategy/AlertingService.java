package com.ratelimiter.pattern.strategy;

import com.ratelimiter.kafka.event.AlertTriggeredEvent;
import com.ratelimiter.kafka.event.RateLimitDecisionEvent;
import com.ratelimiter.pattern.observer.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * PATTERN: Strategy — Context
 *
 * Runs all registered AlertStrategy implementations against each event.
 * New alert rules are plugged in simply by adding a new @Component bean.
 *
 * Circular dependency fix:
 *   EventPublisher collects all EventObservers (incl. AlertObserver)
 *   AlertObserver -> AlertingService -> EventPublisher  <- cycle!
 *
 * Solution: inject EventPublisher @Lazy — Spring injects a proxy instead of
 * the real bean at construction time, deferring resolution until first call.
 * The real bean is available by then because the context is fully started.
 */
@Slf4j
@Service
public class AlertingService {

    private final List<AlertStrategy> strategies;
    private final EventPublisher eventPublisher;

    @Autowired
    public AlertingService(List<AlertStrategy> strategies,
                           @Lazy EventPublisher eventPublisher) {
        this.strategies = strategies;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Evaluate all active alert strategies and publish any triggered alerts.
     * Called by the AlertObserver after each rate limit decision.
     */
    public void evaluate(RateLimitDecisionEvent event) {
        strategies.stream()
                .filter(AlertStrategy::isEnabled)
                .forEach(strategy -> {
                    try {
                        Optional<AlertTriggeredEvent> alert = strategy.evaluate(event);
                        alert.ifPresent(a -> {
                            a.setSourceInstanceId(event.getSourceInstanceId());
                            a.setCorrelationId(event.getCorrelationId());
                            a.setCausationId(event.getEventId());
                            log.info("Alert triggered: strategy={} user={} type={}",
                                    strategy.strategyName(), event.getUserId(), a.getAlertType());
                            eventPublisher.publish(a);
                        });
                    } catch (Exception e) {
                        log.error("Strategy {} threw exception: {}", strategy.strategyName(), e.getMessage(), e);
                    }
                });
    }
}