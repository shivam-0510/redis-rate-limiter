package com.ratelimiter.pattern.chain;

import com.ratelimiter.model.RateLimitResult;
import com.ratelimiter.pattern.chain.RateLimitHandlers.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * PATTERN: Chain of Responsibility — Chain Builder
 *
 * Assembles handlers into a linked chain and provides a single entry point.
 * Pipeline: Whitelist → Blacklist → IpThrottle → PolicyEnrichment → [TokenBucket in service]
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitChain {

    private final WhitelistHandler whitelistHandler;
    private final BlacklistHandler blacklistHandler;
    private final IpThrottleHandler ipThrottleHandler;
    private final PolicyEnrichmentHandler policyEnrichmentHandler;

    // Terminal handler — calls into RateLimiterService (set by the service itself)
    private RateLimitHandler terminalHandler;

    @PostConstruct
    public void buildChain() {
        // Link the chain: each handler's "next" points to the following handler
        whitelistHandler.setNext(blacklistHandler);
        blacklistHandler.setNext(ipThrottleHandler);
        ipThrottleHandler.setNext(policyEnrichmentHandler);
        // policyEnrichmentHandler's next is set to terminalHandler (token bucket) by the service

        log.info("Rate limit chain assembled: Whitelist → Blacklist → IpThrottle → PolicyEnrichment → TokenBucket");
    }

    /**
     * Called by RateLimiterService to inject the terminal (token bucket) handler.
     */
    public void setTerminalHandler(RateLimitHandler terminal) {
        this.terminalHandler = terminal;
        policyEnrichmentHandler.setNext(terminal);
        log.info("Terminal handler set: {}", terminal.getClass().getSimpleName());
    }

    /**
     * Execute the full chain for a rate limit request.
     */
    public RateLimitResult execute(RateLimitContext ctx) {
        // Set up the proceed() chain by wiring each handler's "next" into the context
        return whitelistHandler.handle(ctx);
    }

    public WhitelistHandler getWhitelistHandler() { return whitelistHandler; }
    public BlacklistHandler getBlacklistHandler()  { return blacklistHandler; }
}