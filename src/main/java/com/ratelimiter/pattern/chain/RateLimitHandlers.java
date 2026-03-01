package com.ratelimiter.pattern.chain;

import com.ratelimiter.model.RateLimitPolicy;
import com.ratelimiter.model.RateLimitResult;
import com.ratelimiter.service.PolicyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PATTERN: Chain of Responsibility — concrete handlers.
 *
 * Handler 1 — WhitelistHandler
 * Handler 2 — BlacklistHandler
 * Handler 3 — IpThrottleHandler
 * Handler 4 — PolicyEnrichmentHandler
 * Handler 5 — ValidationHandler (terminal: Token Bucket lives in RateLimiterService)
 */
public class RateLimitHandlers {

    // ─── Abstract Base ─────────────────────────────────────

    @Slf4j
    public abstract static class AbstractRateLimitHandler implements RateLimitHandler {
        protected RateLimitHandler next;

        @Override
        public void setNext(RateLimitHandler next) { this.next = next; }

        protected RateLimitResult passToNext(RateLimitContext ctx) {
            return ctx.proceed();
        }
    }

    // ─── Handler 1: Whitelist ──────────────────────────────

    @Slf4j
    @Component
    public static class WhitelistHandler extends AbstractRateLimitHandler {

        // In production this would be Redis-backed or DB-backed
        private final Set<String> whitelistedUsers = ConcurrentHashMap.newKeySet();

        @Override
        public RateLimitResult handle(RateLimitContext ctx) {
            if (whitelistedUsers.contains(ctx.getUserId())) {
                ctx.setWhitelisted(true);
                log.debug("WHITELIST hit for user={}", ctx.getUserId());
                // Short-circuit: bypass all rate limiting for whitelisted users
                return RateLimitResult.builder()
                        .allowed(true)
                        .tokensRemaining(Long.MAX_VALUE)
                        .capacity(Long.MAX_VALUE)
                        .refillRate(Long.MAX_VALUE)
                        .retryAfterSeconds(0)
                        .userId(ctx.getUserId())
                        .requestTimestamp(System.currentTimeMillis())
                        .instanceId("whitelist")
                        .build();
            }
            return passToNext(ctx);
        }

        public void addToWhitelist(String userId) { whitelistedUsers.add(userId); }
        public void removeFromWhitelist(String userId) { whitelistedUsers.remove(userId); }
        public Set<String> getWhitelist() { return Set.copyOf(whitelistedUsers); }

        @Override
        public String handlerName() { return "WhitelistHandler"; }
    }

    // ─── Handler 2: Blacklist ──────────────────────────────

    @Slf4j
    @Component
    public static class BlacklistHandler extends AbstractRateLimitHandler {

        private final ConcurrentHashMap<String, String> blacklistedUsers = new ConcurrentHashMap<>();

        @Override
        public RateLimitResult handle(RateLimitContext ctx) {
            String reason = blacklistedUsers.get(ctx.getUserId());
            if (reason != null) {
                ctx.setBlacklisted(true);
                ctx.setBlacklistReason(reason);
                log.warn("BLACKLIST hit for user={} reason={}", ctx.getUserId(), reason);
                return RateLimitResult.builder()
                        .allowed(false)
                        .tokensRemaining(0)
                        .capacity(0)
                        .refillRate(0)
                        .retryAfterSeconds(3600) // 1 hour
                        .userId(ctx.getUserId())
                        .requestTimestamp(System.currentTimeMillis())
                        .instanceId("blacklist")
                        .build();
            }
            return passToNext(ctx);
        }

        public void addToBlacklist(String userId, String reason) { blacklistedUsers.put(userId, reason); }
        public void removeFromBlacklist(String userId) { blacklistedUsers.remove(userId); }
        public ConcurrentHashMap<String, String> getBlacklist() { return blacklistedUsers; }

        @Override
        public String handlerName() { return "BlacklistHandler"; }
    }

    // ─── Handler 3: IP Throttle ───────────────────────────

    @Slf4j
    @Component
    public static class IpThrottleHandler extends AbstractRateLimitHandler {

        @Value("${app.rate-limiter.ip-throttle-rps:1000}")
        private int maxRpsPerIp;

        private final ConcurrentHashMap<String, AtomicLong> ipRequestCounts = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Long> ipWindowStart = new ConcurrentHashMap<>();

        @Override
        public RateLimitResult handle(RateLimitContext ctx) {
            if (ctx.getClientIp() == null || ctx.getClientIp().isBlank()) {
                return passToNext(ctx);
            }

            String ip = ctx.getClientIp();
            long now = System.currentTimeMillis();

            long start = ipWindowStart.computeIfAbsent(ip, k -> now);
            if (now - start > 1000) {
                ipRequestCounts.put(ip, new AtomicLong(0));
                ipWindowStart.put(ip, now);
            }

            long count = ipRequestCounts.computeIfAbsent(ip, k -> new AtomicLong(0)).incrementAndGet();

            if (count > maxRpsPerIp) {
                ctx.setIpThrottled(true);
                log.warn("IP_THROTTLE hit for ip={} count={}", ip, count);
                return RateLimitResult.builder()
                        .allowed(false)
                        .tokensRemaining(0)
                        .capacity(maxRpsPerIp)
                        .refillRate(maxRpsPerIp)
                        .retryAfterSeconds(1)
                        .userId(ctx.getUserId())
                        .requestTimestamp(System.currentTimeMillis())
                        .instanceId("ip-throttle")
                        .build();
            }

            return passToNext(ctx);
        }

        @Override
        public String handlerName() { return "IpThrottleHandler"; }
    }

    // ─── Handler 4: Policy Enrichment ─────────────────────

    @Slf4j
    @Component
    public static class PolicyEnrichmentHandler extends AbstractRateLimitHandler {

        private final PolicyService policyService;

        public PolicyEnrichmentHandler(PolicyService policyService) {
            this.policyService = policyService;
        }

        @Override
        public RateLimitResult handle(RateLimitContext ctx) {
            // Enrich context with resolved policy before it hits the token bucket
            RateLimitPolicy policy = policyService.getPolicy(ctx.getUserId());

            if (!policy.isActive()) {
                log.warn("Policy INACTIVE for user={}", ctx.getUserId());
                return RateLimitResult.builder()
                        .allowed(false)
                        .tokensRemaining(0)
                        .capacity(0)
                        .refillRate(0)
                        .retryAfterSeconds(0)
                        .userId(ctx.getUserId())
                        .requestTimestamp(System.currentTimeMillis())
                        .instanceId("policy-check")
                        .build();
            }

            ctx.setResolvedPolicy(policy);
            return passToNext(ctx);
        }

        @Override
        public String handlerName() { return "PolicyEnrichmentHandler"; }
    }
}