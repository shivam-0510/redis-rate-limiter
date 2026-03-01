package com.ratelimiter.service;

import com.ratelimiter.dto.Dtos;
import com.ratelimiter.model.RateLimitResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SimulationService {

    private final RateLimiterService rateLimiterService;
    private final PolicyService policyService;

    public Dtos.SimulateResult simulate(String userId, int requestCount, int delayMs) {
        List<Dtos.RateLimitResponse> results = new ArrayList<>();
        int allowedCount = 0;
        int blockedCount = 0;

        // Ensure user has a policy
        policyService.getPolicy(userId);

        // Reset bucket for clean simulation
        rateLimiterService.resetBucket(userId);

        for (int i = 0; i < requestCount; i++) {
            RateLimitResult result = rateLimiterService.consume(userId, 1);

            Dtos.RateLimitResponse response = Dtos.RateLimitResponse.builder()
                    .allowed(result.isAllowed())
                    .tokensRemaining(result.getTokensRemaining())
                    .capacity(result.getCapacity())
                    .refillRate(result.getRefillRate())
                    .retryAfterSeconds(result.getRetryAfterSeconds())
                    .userId(userId)
                    .requestTimestamp(result.getRequestTimestamp())
                    .instanceId(result.getInstanceId())
                    .message(result.isAllowed() ? "Request #" + (i + 1) + " allowed" : "Request #" + (i + 1) + " blocked - rate limit exceeded")
                    .build();

            results.add(response);

            if (result.isAllowed()) {
                allowedCount++;
            } else {
                blockedCount++;
            }

            if (delayMs > 0 && i < requestCount - 1) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        double allowRate = requestCount > 0 ? (double) allowedCount / requestCount * 100 : 0;

        return Dtos.SimulateResult.builder()
                .userId(userId)
                .totalRequests(requestCount)
                .allowedCount(allowedCount)
                .blockedCount(blockedCount)
                .allowRate(Math.round(allowRate * 100.0) / 100.0)
                .results(results)
                .build();
    }
}