package com.ratelimiter.pattern.chain;

import com.ratelimiter.model.RateLimitResult;

/**
 * PATTERN: Chain of Responsibility
 *
 * Each handler in the chain has the opportunity to:
 * - Short-circuit the chain and return a result immediately (e.g. whitelist)
 * - Enrich the request context and pass it along
 * - Block the request entirely without calling Redis
 *
 * Chain order:
 *   WhitelistHandler → IpThrottleHandler → PolicyEnrichmentHandler → TokenBucketHandler
 */
public interface RateLimitHandler {

    /**
     * Process the request context.
     * Call context.proceed() to pass to the next handler.
     * Return a result directly to short-circuit the chain.
     */
    RateLimitResult handle(RateLimitContext context);

    /**
     * Set the next handler in the chain.
     */
    void setNext(RateLimitHandler next);

    /**
     * Handler name for logging.
     */
    String handlerName();
}