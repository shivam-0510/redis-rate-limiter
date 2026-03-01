package com.ratelimiter.service;

import com.ratelimiter.kafka.event.BucketResetEvent;
import com.ratelimiter.kafka.event.RateLimitDecisionEvent;
import com.ratelimiter.model.RateLimitPolicy;
import com.ratelimiter.model.RateLimitResult;
import com.ratelimiter.pattern.chain.RateLimitChain;
import com.ratelimiter.pattern.chain.RateLimitContext;
import com.ratelimiter.pattern.chain.RateLimitHandler;
import com.ratelimiter.pattern.factory.EventFactory;
import com.ratelimiter.pattern.observer.EventPublisher;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core rate limiting service.
 *
 * Design patterns applied here:
 * - Chain of Responsibility: Whitelist -> Blacklist -> IpThrottle -> PolicyEnrich -> TokenBucket
 * - Observer: publishes domain events to all registered observers (Kafka, Metrics, Alerts)
 * - Factory: delegates event construction to EventFactory
 *
 * @Lazy on EventPublisher and EventFactory breaks the startup circular
 * dependency that flows through PolicyService -> EventPublisher -> AlertObserver
 * -> AlertingService -> EventPublisher.
 */
@Slf4j
@Service
public class RateLimiterService {

    private final RedisTemplate<String, String> redisTemplate;
    private final DefaultRedisScript<List> tokenBucketScript;
    private final PolicyService policyService;
    private final EventPublisher eventPublisher;
    private final EventFactory eventFactory;
    private final RateLimitChain rateLimitChain;

    @Value("${app.rate-limiter.key-prefix:rl:}")
    private String keyPrefix;

    private final AtomicLong totalRequests   = new AtomicLong(0);
    private final AtomicLong allowedRequests = new AtomicLong(0);
    private final AtomicLong blockedRequests = new AtomicLong(0);
    private final long startTime = System.currentTimeMillis();
    private final String instanceId = generateInstanceId();

    @Autowired
    public RateLimiterService(RedisTemplate<String, String> redisTemplate,
                              DefaultRedisScript<List> tokenBucketScript,
                              @Lazy PolicyService policyService,
                              @Lazy EventPublisher eventPublisher,
                              @Lazy EventFactory eventFactory,
                              @Lazy RateLimitChain rateLimitChain) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
        this.policyService = policyService;
        this.eventPublisher = eventPublisher;
        this.eventFactory = eventFactory;
        this.rateLimitChain = rateLimitChain;
    }

    @PostConstruct
    public void init() {
        rateLimitChain.setTerminalHandler(new RateLimitHandler() {
            @Override
            public RateLimitResult handle(RateLimitContext ctx) {
                return executeTokenBucket(ctx);
            }
            @Override public void setNext(RateLimitHandler next) {}
            @Override public String handlerName() { return "TokenBucketHandler"; }
        });
        log.info("RateLimiterService registered as terminal chain handler. InstanceId={}", instanceId);
    }

    public RateLimitResult consume(String userId, int tokensRequested) {
        return consume(userId, tokensRequested, null, null, null);
    }

    public RateLimitResult consume(String userId, int tokensRequested,
                                   String clientIp, String endpoint, String correlationId) {
        long start = System.currentTimeMillis();
        totalRequests.incrementAndGet();

        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString();

        RateLimitContext ctx = RateLimitContext.builder()
                .userId(userId)
                .tokensRequested(tokensRequested)
                .clientIp(clientIp)
                .endpoint(endpoint)
                .correlationId(corrId)
                .requestStartMs(start)
                .build();

        RateLimitResult result = rateLimitChain.execute(ctx);
        long latencyMs = System.currentTimeMillis() - start;

        if (result.isAllowed()) allowedRequests.incrementAndGet();
        else blockedRequests.incrementAndGet();

        RateLimitDecisionEvent event = eventFactory.createDecisionEvent(
                result, tokensRequested, corrId, clientIp, endpoint, latencyMs);
        String tier = ctx.getResolvedPolicy() != null ? ctx.getResolvedPolicy().getTier() : "UNKNOWN";
        event.setTier(tier);
        eventPublisher.publish(event);

        return result;
    }

    @SuppressWarnings("unchecked")
    private RateLimitResult executeTokenBucket(RateLimitContext ctx) {
        RateLimitPolicy policy = ctx.getResolvedPolicy() != null
                ? ctx.getResolvedPolicy()
                : policyService.getPolicy(ctx.getUserId());

        String key = keyPrefix + "bucket:" + ctx.getUserId();
        long now = System.currentTimeMillis() / 1000;
        long ttl = Math.max(policy.getRefillPeriodSeconds() * 100, 3600);

        try {
            List<Long> result = redisTemplate.execute(
                    tokenBucketScript,
                    Collections.singletonList(key),
                    String.valueOf(policy.getCapacity()),
                    String.valueOf(policy.getRefillRate()),
                    String.valueOf(policy.getRefillPeriodSeconds()),
                    String.valueOf(ctx.getTokensRequested()),
                    String.valueOf(now),
                    String.valueOf(ttl)
            );

            boolean allowed = result.get(0) == 1L;
            long tokensRemaining = result.get(1);
            long retryAfter      = result.get(2);

            log.debug("TokenBucket {} user={} tokens_remaining={}",
                    allowed ? "ALLOWED" : "BLOCKED", ctx.getUserId(), tokensRemaining);

            return RateLimitResult.builder()
                    .allowed(allowed)
                    .tokensRemaining(tokensRemaining)
                    .capacity(policy.getCapacity())
                    .refillRate(policy.getRefillRate())
                    .retryAfterSeconds(retryAfter)
                    .userId(ctx.getUserId())
                    .requestTimestamp(System.currentTimeMillis())
                    .instanceId(instanceId)
                    .build();

        } catch (Exception e) {
            log.error("Redis error for user={}, failing open: {}", ctx.getUserId(), e.getMessage());
            return RateLimitResult.builder()
                    .allowed(true)
                    .tokensRemaining(policy.getCapacity())
                    .capacity(policy.getCapacity())
                    .refillRate(policy.getRefillRate())
                    .retryAfterSeconds(0)
                    .userId(ctx.getUserId())
                    .requestTimestamp(System.currentTimeMillis())
                    .instanceId(instanceId)
                    .build();
        }
    }

    public Map<String, Object> getBucketStatus(String userId) {
        String key = keyPrefix + "bucket:" + userId;
        RateLimitPolicy policy = policyService.getPolicy(userId);

        Map<Object, Object> data = redisTemplate.opsForHash().entries(key);
        long tokens = data.containsKey("tokens")
                ? Long.parseLong((String) data.get("tokens"))
                : policy.getCapacity();

        return Map.of(
                "userId", userId,
                "tokens", tokens,
                "capacity", policy.getCapacity(),
                "refillRate", policy.getRefillRate(),
                "refillPeriodSeconds", policy.getRefillPeriodSeconds(),
                "tier", policy.getTier(),
                "utilizationPercent", policy.getCapacity() > 0
                        ? ((double)(policy.getCapacity() - tokens) / policy.getCapacity()) * 100 : 0,
                "active", policy.isActive()
        );
    }

    public Map<String, Object> getSystemStats() {
        long total   = totalRequests.get();
        long blocked = blockedRequests.get();
        double blockRate = total > 0 ? (double) blocked / total * 100 : 0;

        String redisStatus = "UP";
        try { redisTemplate.opsForValue().get("health_check"); }
        catch (Exception e) { redisStatus = "DOWN"; }

        return Map.of(
                "totalRequests",   total,
                "allowedRequests", allowedRequests.get(),
                "blockedRequests", blocked,
                "blockRate",       Math.round(blockRate * 100.0) / 100.0,
                "activeBuckets",   policyService.getPolicyCount(),
                "redisStatus",     redisStatus,
                "instanceId",      instanceId,
                "uptimeSeconds",   (System.currentTimeMillis() - startTime) / 1000
        );
    }

    public void resetBucket(String userId) {
        String key = keyPrefix + "bucket:" + userId;
        RateLimitPolicy policy = policyService.getPolicy(userId);

        Map<Object, Object> data = redisTemplate.opsForHash().entries(key);
        long tokensBefore = data.containsKey("tokens")
                ? Long.parseLong((String) data.get("tokens")) : policy.getCapacity();

        redisTemplate.delete(key);

        BucketResetEvent event = eventFactory.createBucketResetEvent(
                userId, "system", "Manual reset", tokensBefore, policy.getCapacity());
        eventPublisher.publish(event);

        log.info("Bucket reset for user={}", userId);
    }

    public String getInstanceId() { return instanceId; }

    private String generateInstanceId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
        } catch (Exception e) {
            return "instance-" + System.currentTimeMillis();
        }
    }
}