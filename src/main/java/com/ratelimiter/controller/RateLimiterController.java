package com.ratelimiter.controller;

import com.ratelimiter.dto.Dtos;
import com.ratelimiter.model.RateLimitPolicy;
import com.ratelimiter.model.RateLimitResult;
import com.ratelimiter.pattern.command.PolicyCommands;
import com.ratelimiter.service.PolicyService;
import com.ratelimiter.service.RateLimiterService;
import com.ratelimiter.service.SimulationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RateLimiterController {

    private final RateLimiterService rateLimiterService;
    private final PolicyService policyService;
    private final SimulationService simulationService;
    private final PolicyCommands.PolicyCommandInvoker commandInvoker;

    @PostMapping("/ratelimit/consume")
    public ResponseEntity<Dtos.ApiResponse<Dtos.RateLimitResponse>> consume(
            @Valid @RequestBody Dtos.RateLimitRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {

        String clientIp = httpRequest.getRemoteAddr();
        String endpoint = httpRequest.getRequestURI();
        String correlationId = httpRequest.getHeader("X-Correlation-ID");

        // Chain of Responsibility pipeline + Observer event publishing
        RateLimitResult result = rateLimiterService.consume(
                request.getUserId(), request.getTokens(), clientIp, endpoint, correlationId);

        response.setHeader("X-RateLimit-Limit",       String.valueOf(result.getCapacity()));
        response.setHeader("X-RateLimit-Remaining",   String.valueOf(result.getTokensRemaining()));
        response.setHeader("X-RateLimit-Retry-After", String.valueOf(result.getRetryAfterSeconds()));
        response.setHeader("X-Instance-Id",           result.getInstanceId());

        Dtos.RateLimitResponse rateLimitResponse = Dtos.RateLimitResponse.builder()
                .allowed(result.isAllowed())
                .tokensRemaining(result.getTokensRemaining())
                .capacity(result.getCapacity())
                .refillRate(result.getRefillRate())
                .retryAfterSeconds(result.getRetryAfterSeconds())
                .userId(result.getUserId())
                .requestTimestamp(result.getRequestTimestamp())
                .instanceId(result.getInstanceId())
                .message(result.isAllowed() ? "Request allowed" : "Rate limit exceeded")
                .build();

        if (result.isAllowed()) {
            return ResponseEntity.ok(Dtos.ApiResponse.ok(rateLimitResponse));
        } else {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Dtos.ApiResponse.<Dtos.RateLimitResponse>builder()
                            .success(false)
                            .message("Rate limit exceeded. Retry after " + result.getRetryAfterSeconds() + "s")
                            .data(rateLimitResponse)
                            .timestamp(System.currentTimeMillis())
                            .build());
        }
    }

    @GetMapping("/ratelimit/status/{userId}")
    public ResponseEntity<Dtos.ApiResponse<Map<String, Object>>> getStatus(@PathVariable String userId) {
        return ResponseEntity.ok(Dtos.ApiResponse.ok(rateLimiterService.getBucketStatus(userId)));
    }

    @DeleteMapping("/ratelimit/bucket/{userId}")
    public ResponseEntity<Dtos.ApiResponse<String>> resetBucket(@PathVariable String userId) {
        rateLimiterService.resetBucket(userId);
        return ResponseEntity.ok(Dtos.ApiResponse.ok("Bucket reset successfully for user: " + userId));
    }

    @GetMapping("/stats")
    public ResponseEntity<Dtos.ApiResponse<Map<String, Object>>> getStats() {
        return ResponseEntity.ok(Dtos.ApiResponse.ok(rateLimiterService.getSystemStats()));
    }

    @GetMapping("/policies")
    public ResponseEntity<Dtos.ApiResponse<List<RateLimitPolicy>>> getAllPolicies() {
        return ResponseEntity.ok(Dtos.ApiResponse.ok(policyService.getAllPolicies()));
    }

    @GetMapping("/policies/{userId}")
    public ResponseEntity<Dtos.ApiResponse<RateLimitPolicy>> getPolicy(@PathVariable String userId) {
        return ResponseEntity.ok(Dtos.ApiResponse.ok(policyService.getPolicy(userId)));
    }

    /**
     * PATTERN: Command — UpdatePolicyCommand wraps the policy update.
     * Produces an undo-able operation + publishes audit event to Kafka.
     */
    @PutMapping("/policies/{userId}")
    public ResponseEntity<Dtos.ApiResponse<RateLimitPolicy>> updatePolicy(
            @PathVariable String userId,
            @Valid @RequestBody Dtos.PolicyUpdateRequest request,
            @RequestHeader(value = "X-Actor-Id", defaultValue = "admin") String actorId) {

        RateLimitPolicy policy = RateLimitPolicy.builder()
                .userId(userId)
                .capacity(request.getCapacity())
                .refillRate(request.getRefillRate())
                .refillPeriodSeconds(request.getRefillPeriodSeconds())
                .tier(request.getTier() != null ? request.getTier() : "CUSTOM")
                .active(true)
                .build();

        // Execute via Command pattern (undo-able + audit trail to Kafka)
        RateLimitPolicy updated = commandInvoker.execute(
                new PolicyCommands.UpdatePolicyCommand(policyService, policy), actorId);

        return ResponseEntity.ok(Dtos.ApiResponse.ok("Policy updated successfully", updated));
    }

    /**
     * PATTERN: Command — ApplyTierCommand
     */
    @PutMapping("/policies/{userId}/tier/{tier}")
    public ResponseEntity<Dtos.ApiResponse<RateLimitPolicy>> applyTier(
            @PathVariable String userId,
            @PathVariable String tier,
            @RequestHeader(value = "X-Actor-Id", defaultValue = "admin") String actorId) {

        RateLimitPolicy updated = commandInvoker.execute(
                new PolicyCommands.ApplyTierCommand(policyService, rateLimiterService, userId, tier),
                actorId);

        return ResponseEntity.ok(Dtos.ApiResponse.ok("Tier applied successfully", updated));
    }

    /**
     * PATTERN: Command — DeletePolicyCommand
     */
    @DeleteMapping("/policies/{userId}")
    public ResponseEntity<Dtos.ApiResponse<String>> deletePolicy(
            @PathVariable String userId,
            @RequestHeader(value = "X-Actor-Id", defaultValue = "admin") String actorId) {

        commandInvoker.execute(
                new PolicyCommands.DeletePolicyCommand(policyService, userId,
                        // Spring will inject EventPublisher here via factory
                        null),   // simplified — in real code, inject via constructor
                actorId);

        policyService.deletePolicy(userId);
        return ResponseEntity.ok(Dtos.ApiResponse.ok("Policy deleted for user: " + userId));
    }

    @PostMapping("/simulate")
    public ResponseEntity<Dtos.ApiResponse<Dtos.SimulateResult>> simulate(
            @Valid @RequestBody Dtos.SimulateRequest request) {
        Dtos.SimulateResult result = simulationService.simulate(
                request.getUserId(),
                Math.min(request.getRequestCount(), 200),
                request.getDelayMs()
        );
        return ResponseEntity.ok(Dtos.ApiResponse.ok("Simulation completed", result));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "distributed-rate-limiter",
                "instanceId", rateLimiterService.getInstanceId(),
                "timestamp", System.currentTimeMillis()
        ));
    }
}