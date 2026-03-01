package com.ratelimiter.pattern.command;

import com.ratelimiter.kafka.event.AuditEvent;
import com.ratelimiter.kafka.event.BaseEvent;
import com.ratelimiter.kafka.event.PolicyChangedEvent;
import com.ratelimiter.model.RateLimitPolicy;
import com.ratelimiter.pattern.observer.EventPublisher;
import com.ratelimiter.service.PolicyService;
import com.ratelimiter.service.RateLimiterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * PATTERN: Command
 *
 * Each policy mutation is encapsulated as a Command object with:
 * - execute() — perform the operation
 * - undo()    — reverse the operation (full undo history)
 *
 * Benefits:
 * - Audit trail: every command is logged as an AuditEvent to Kafka
 * - Undo/redo stack for policy management UI
 * - Commands can be queued, retried, or replayed
 */
public class PolicyCommands {

    // ─── Command Interface ────────────────────────────────

    public interface PolicyCommand {
        RateLimitPolicy execute();
        void undo();
        String describe();
    }

    // ─── Command Invoker ──────────────────────────────────

    @Slf4j
    @Component
    public static class PolicyCommandInvoker {

        private final EventPublisher eventPublisher;
        private final Deque<PolicyCommand> history = new ArrayDeque<>();
        private static final int MAX_HISTORY = 50;

        public PolicyCommandInvoker(EventPublisher eventPublisher) {
            this.eventPublisher = eventPublisher;
        }

        public RateLimitPolicy execute(PolicyCommand command, String actorId) {
            log.info("Executing command: {} by actor={}", command.describe(), actorId);

            RateLimitPolicy result = command.execute();

            // Publish audit event to Kafka
            AuditEvent audit = AuditEvent.builder()
                    .eventId(BaseEvent.newEventId())
                    .eventType("AUDIT")
                    .occurredAt(Instant.now())
                    .action(AuditEvent.Action.POLICY_UPDATED)
                    .actorId(actorId)
                    .targetUserId(result.getUserId())
                    .targetResource("policy:" + result.getUserId())
                    .after(Map.of(
                            "capacity", result.getCapacity(),
                            "refillRate", result.getRefillRate(),
                            "tier", result.getTier()
                    ))
                    .reason(command.describe())
                    .success(true)
                    .build();
            eventPublisher.publish(audit);

            // Push to undo stack
            if (history.size() >= MAX_HISTORY) history.pollFirst();
            history.push(command);

            return result;
        }

        public boolean undo(String actorId) {
            if (history.isEmpty()) {
                log.warn("Undo requested but history is empty");
                return false;
            }
            PolicyCommand last = history.pop();
            log.info("Undoing command: {} by actor={}", last.describe(), actorId);
            last.undo();

            AuditEvent audit = AuditEvent.builder()
                    .eventId(BaseEvent.newEventId())
                    .eventType("AUDIT")
                    .occurredAt(Instant.now())
                    .action(AuditEvent.Action.POLICY_UPDATED)
                    .actorId(actorId)
                    .reason("UNDO: " + last.describe())
                    .success(true)
                    .build();
            eventPublisher.publish(audit);

            return true;
        }

        public int historySize() { return history.size(); }
    }

    // ─── Command 1: Update Policy ─────────────────────────

    @Slf4j
    public static class UpdatePolicyCommand implements PolicyCommand {

        private final PolicyService policyService;
        private final RateLimitPolicy newPolicy;
        private RateLimitPolicy previousPolicy;

        public UpdatePolicyCommand(PolicyService policyService, RateLimitPolicy newPolicy) {
            this.policyService = policyService;
            this.newPolicy = newPolicy;
        }

        @Override
        public RateLimitPolicy execute() {
            previousPolicy = policyService.getPolicy(newPolicy.getUserId());
            return policyService.updatePolicy(newPolicy);
        }

        @Override
        public void undo() {
            if (previousPolicy != null) {
                policyService.updatePolicy(previousPolicy);
                log.info("UNDO UpdatePolicy for user={}", newPolicy.getUserId());
            }
        }

        @Override
        public String describe() {
            return "UpdatePolicy userId=" + newPolicy.getUserId() + " tier=" + newPolicy.getTier();
        }
    }

    // ─── Command 2: Apply Tier ────────────────────────────

    @Slf4j
    public static class ApplyTierCommand implements PolicyCommand {

        private final PolicyService policyService;
        private final RateLimiterService rateLimiterService;
        private final String userId;
        private final String newTier;
        private RateLimitPolicy previousPolicy;

        public ApplyTierCommand(PolicyService policyService, RateLimiterService rateLimiterService,
                                String userId, String newTier) {
            this.policyService = policyService;
            this.rateLimiterService = rateLimiterService;
            this.userId = userId;
            this.newTier = newTier;
        }

        @Override
        public RateLimitPolicy execute() {
            previousPolicy = policyService.getPolicy(userId);
            RateLimitPolicy policy = RateLimitPolicy.forTier(userId, newTier);
            RateLimitPolicy updated = policyService.updatePolicy(policy);
            rateLimiterService.resetBucket(userId);
            return updated;
        }

        @Override
        public void undo() {
            if (previousPolicy != null) {
                policyService.updatePolicy(previousPolicy);
                rateLimiterService.resetBucket(userId);
                log.info("UNDO ApplyTier for user={}, reverted to tier={}", userId, previousPolicy.getTier());
            }
        }

        @Override
        public String describe() {
            return "ApplyTier userId=" + userId + " tier=" + newTier;
        }
    }

    // ─── Command 3: Delete Policy ─────────────────────────

    @Slf4j
    public static class DeletePolicyCommand implements PolicyCommand {

        private final PolicyService policyService;
        private final String userId;
        private RateLimitPolicy deletedPolicy;
        private final EventPublisher eventPublisher;

        public DeletePolicyCommand(PolicyService policyService, String userId, EventPublisher eventPublisher) {
            this.policyService = policyService;
            this.userId = userId;
            this.eventPublisher = eventPublisher;
        }

        @Override
        public RateLimitPolicy execute() {
            deletedPolicy = policyService.getPolicy(userId);
            policyService.deletePolicy(userId);

            // Publish policy change event
            PolicyChangedEvent event = PolicyChangedEvent.builder()
                    .eventId(BaseEvent.newEventId())
                    .eventType("POLICY_CHANGED")
                    .occurredAt(Instant.now())
                    .userId(userId)
                    .changeType(PolicyChangedEvent.ChangeType.DELETED)
                    .previousPolicy(new PolicyChangedEvent.PolicySnapshot(
                            deletedPolicy.getCapacity(), deletedPolicy.getRefillRate(),
                            deletedPolicy.getRefillPeriodSeconds(), deletedPolicy.getTier(), deletedPolicy.isActive()
                    ))
                    .build();
            eventPublisher.publish(event);

            return deletedPolicy;
        }

        @Override
        public void undo() {
            if (deletedPolicy != null) {
                policyService.updatePolicy(deletedPolicy);
                log.info("UNDO DeletePolicy for user={}", userId);
            }
        }

        @Override
        public String describe() {
            return "DeletePolicy userId=" + userId;
        }
    }
}