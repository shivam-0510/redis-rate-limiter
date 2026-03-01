package com.ratelimiter.pattern.chain;

import com.ratelimiter.model.RateLimitPolicy;
import com.ratelimiter.model.RateLimitResult;
import lombok.Builder;
import lombok.Data;

/**
 * Mutable context object passed through the Chain of Responsibility.
 * Handlers can read + enrich this context before passing it along.
 */
@Data
@Builder
public class RateLimitContext {

    // ─── Input ────────────────────────────────────────────
    private final String userId;
    private final int tokensRequested;
    private final String clientIp;
    private final String endpoint;
    private final String userAgent;
    private final String correlationId;
    private final long requestStartMs;

    // ─── Enriched by handlers ─────────────────────────────
    private RateLimitPolicy resolvedPolicy;
    private boolean whitelisted;
    private boolean blacklisted;
    private String blacklistReason;
    private boolean ipThrottled;

    // ─── Chain control ────────────────────────────────────
    /** Next handler in the chain */
    private RateLimitHandler nextHandler;

    /**
     * Proceed to the next handler.
     * If no next handler, returns null (chain exhausted — shouldn't happen).
     */
    public RateLimitResult proceed() {
        if (nextHandler != null) {
            return nextHandler.handle(this);
        }
        return null;
    }
}