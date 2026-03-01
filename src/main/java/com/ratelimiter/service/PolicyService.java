package com.ratelimiter.service;

import com.ratelimiter.kafka.event.PolicyChangedEvent;
import com.ratelimiter.model.RateLimitPolicy;
import com.ratelimiter.pattern.factory.EventFactory;
import com.ratelimiter.pattern.observer.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-user rate limit policies.
 *
 * EventPublisher and EventFactory are injected @Lazy to avoid participating
 * in the startup circular dependency chain:
 *   PolicyEnrichmentHandler -> PolicyService -> EventPublisher -> AlertObserver
 *   -> AlertingService -> EventPublisher (cycle)
 *
 * With @Lazy, Spring injects a proxy that resolves after the context is ready.
 */
@Slf4j
@Service
public class PolicyService {

    private final RedisTemplate<String, String> redisTemplate;
    private final EventPublisher eventPublisher;
    private final EventFactory eventFactory;

    @Value("${app.rate-limiter.key-prefix:rl:}")
    private String keyPrefix;

    @Value("${app.rate-limiter.default-capacity:100}")
    private long defaultCapacity;

    @Value("${app.rate-limiter.default-refill-rate:10}")
    private long defaultRefillRate;

    private final ConcurrentHashMap<String, RateLimitPolicy> policyCache = new ConcurrentHashMap<>();

    @Autowired
    public PolicyService(RedisTemplate<String, String> redisTemplate,
                         @Lazy EventPublisher eventPublisher,
                         @Lazy EventFactory eventFactory) {
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
        this.eventFactory = eventFactory;
    }

    public RateLimitPolicy getPolicy(String userId) {
        return policyCache.computeIfAbsent(userId, id -> {
            String policyKey = keyPrefix + "policy:" + id;
            Map<Object, Object> data = redisTemplate.opsForHash().entries(policyKey);
            if (!data.isEmpty()) {
                return RateLimitPolicy.builder()
                        .userId(id)
                        .capacity(Long.parseLong((String) data.getOrDefault("capacity", String.valueOf(defaultCapacity))))
                        .refillRate(Long.parseLong((String) data.getOrDefault("refillRate", String.valueOf(defaultRefillRate))))
                        .refillPeriodSeconds(Long.parseLong((String) data.getOrDefault("refillPeriodSeconds", "1")))
                        .tier((String) data.getOrDefault("tier", "FREE"))
                        .active(Boolean.parseBoolean((String) data.getOrDefault("active", "true")))
                        .build();
            }
            return RateLimitPolicy.defaultPolicy(id);
        });
    }

    public RateLimitPolicy updatePolicy(RateLimitPolicy policy) {
        RateLimitPolicy previous = policyCache.get(policy.getUserId());
        boolean isNew = previous == null;

        String policyKey = keyPrefix + "policy:" + policy.getUserId();
        Map<String, String> data = new HashMap<>();
        data.put("capacity",            String.valueOf(policy.getCapacity()));
        data.put("refillRate",          String.valueOf(policy.getRefillRate()));
        data.put("refillPeriodSeconds", String.valueOf(policy.getRefillPeriodSeconds()));
        data.put("tier",                policy.getTier());
        data.put("active",              String.valueOf(policy.isActive()));
        redisTemplate.opsForHash().putAll(policyKey, data);
        policyCache.put(policy.getUserId(), policy);

        PolicyChangedEvent.ChangeType changeType = isNew
                ? PolicyChangedEvent.ChangeType.CREATED
                : PolicyChangedEvent.ChangeType.UPDATED;

        eventPublisher.publish(
                eventFactory.createPolicyChangedEvent(policy, previous, changeType, "system"));

        log.info("Policy {} for user={} tier={}", changeType, policy.getUserId(), policy.getTier());
        return policy;
    }

    public void deletePolicy(String userId) {
        RateLimitPolicy existing = policyCache.remove(userId);
        redisTemplate.delete(keyPrefix + "policy:" + userId);

        if (existing != null) {
            eventPublisher.publish(
                    eventFactory.createPolicyChangedEvent(existing, existing,
                            PolicyChangedEvent.ChangeType.DELETED, "system"));
        }
        log.info("Policy deleted for user={}", userId);
    }

    public List<RateLimitPolicy> getAllPolicies() {
        return new ArrayList<>(policyCache.values());
    }

    public int getPolicyCount() { return policyCache.size(); }

    public List<String> getAllUserIds() { return new ArrayList<>(policyCache.keySet()); }
}