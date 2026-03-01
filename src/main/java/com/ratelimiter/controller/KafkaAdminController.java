package com.ratelimiter.controller;

import com.ratelimiter.dto.Dtos;
import com.ratelimiter.kafka.consumer.AlertConsumer;
import com.ratelimiter.kafka.consumer.RateLimitEventConsumer;
import com.ratelimiter.kafka.consumer.DeadLetterConsumer;
import com.ratelimiter.pattern.chain.RateLimitChain;
import com.ratelimiter.pattern.command.PolicyCommands;
import com.ratelimiter.pattern.decorator.ResilientKafkaProducer;
import com.ratelimiter.pattern.outbox.OutboxRelay;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Kafka & pattern management endpoints.
 * Exposes observability into the Kafka pipeline + design pattern state.
 */
@RestController
@RequestMapping("/api/v1/kafka")
@RequiredArgsConstructor
public class KafkaAdminController {

    private final RateLimitEventConsumer eventConsumer;
    private final AlertConsumer alertConsumer;
    private final DeadLetterConsumer deadLetterConsumer;
    private final ResilientKafkaProducer kafkaProducer;
    private final OutboxRelay outboxRelay;
    private final RateLimitChain rateLimitChain;
    private final PolicyCommands.PolicyCommandInvoker commandInvoker;

    // ─── Consumer Stats ───────────────────────────────────

    /** Aggregated cross-instance metrics from Kafka consumer */
    @GetMapping("/stats/aggregated")
    public ResponseEntity<Dtos.ApiResponse<Map<String, Object>>> getAggregatedStats() {
        return ResponseEntity.ok(Dtos.ApiResponse.ok(eventConsumer.getAggregatedStats()));
    }

    /** Per-user stats aggregated from Kafka stream */
    @GetMapping("/stats/user/{userId}")
    public ResponseEntity<Dtos.ApiResponse<RateLimitEventConsumer.UserStats>> getUserStats(
            @PathVariable String userId) {
        return ResponseEntity.ok(Dtos.ApiResponse.ok(eventConsumer.getUserStats(userId)));
    }

    /** Per-instance breakdown (multi-instance visibility) */
    @GetMapping("/stats/instances")
    public ResponseEntity<Dtos.ApiResponse<Map<String, RateLimitEventConsumer.InstanceStats>>> getInstanceStats() {
        return ResponseEntity.ok(Dtos.ApiResponse.ok(eventConsumer.getInstanceStats()));
    }

    // ─── Alerts ───────────────────────────────────────────

    @GetMapping("/alerts")
    public ResponseEntity<Dtos.ApiResponse<List<?>>> getAlerts(
            @RequestParam(defaultValue = "20") int limit) {
        List<?> alerts = alertConsumer.getRecentAlerts().stream().limit(limit).toList();
        return ResponseEntity.ok(Dtos.ApiResponse.ok(alerts));
    }

    @GetMapping("/alerts/counts")
    public ResponseEntity<Dtos.ApiResponse<Map<String, Long>>> getAlertCounts() {
        return ResponseEntity.ok(Dtos.ApiResponse.ok(alertConsumer.getAlertCounts()));
    }

    // ─── Dead Letter ──────────────────────────────────────

    @GetMapping("/dlq")
    public ResponseEntity<Dtos.ApiResponse<List<DeadLetterConsumer.DeadLetterEntry>>> getDlqMessages() {
        return ResponseEntity.ok(Dtos.ApiResponse.ok(deadLetterConsumer.getDeadLetters()));
    }

    @GetMapping("/dlq/count")
    public ResponseEntity<Dtos.ApiResponse<Integer>> getDlqCount() {
        return ResponseEntity.ok(Dtos.ApiResponse.ok(deadLetterConsumer.getCount()));
    }

    // ─── Infrastructure Health ────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<Dtos.ApiResponse<Map<String, Object>>> getKafkaHealth() {
        return ResponseEntity.ok(Dtos.ApiResponse.ok(Map.of(
                "circuitBreakerState", kafkaProducer.getCircuitBreakerState().name(),
                "circuitBreakerMetrics", Map.of(
                        "failureRate",    kafkaProducer.getCircuitBreaker().getMetrics().getFailureRate(),
                        "callsPermitted", kafkaProducer.getCircuitBreaker().getMetrics().getNumberOfBufferedCalls(),
                        "successCalls",   kafkaProducer.getCircuitBreaker().getMetrics().getNumberOfSuccessfulCalls(),
                        "failedCalls",    kafkaProducer.getCircuitBreaker().getMetrics().getNumberOfFailedCalls()
                ),
                "outboxPendingCount", outboxRelay.pendingCount(),
                "dlqMessageCount",    deadLetterConsumer.getCount()
        )));
    }

    // ─── Chain of Responsibility Management ──────────────

    @PostMapping("/chain/whitelist/{userId}")
    public ResponseEntity<Dtos.ApiResponse<String>> addToWhitelist(@PathVariable String userId) {
        rateLimitChain.getWhitelistHandler().addToWhitelist(userId);
        return ResponseEntity.ok(Dtos.ApiResponse.ok("Added " + userId + " to whitelist"));
    }

    @DeleteMapping("/chain/whitelist/{userId}")
    public ResponseEntity<Dtos.ApiResponse<String>> removeFromWhitelist(@PathVariable String userId) {
        rateLimitChain.getWhitelistHandler().removeFromWhitelist(userId);
        return ResponseEntity.ok(Dtos.ApiResponse.ok("Removed " + userId + " from whitelist"));
    }

    @GetMapping("/chain/whitelist")
    public ResponseEntity<Dtos.ApiResponse<?>> getWhitelist() {
        return ResponseEntity.ok(Dtos.ApiResponse.ok(rateLimitChain.getWhitelistHandler().getWhitelist()));
    }

    @PostMapping("/chain/blacklist/{userId}")
    public ResponseEntity<Dtos.ApiResponse<String>> addToBlacklist(
            @PathVariable String userId,
            @RequestParam(defaultValue = "Manual blacklist") String reason) {
        rateLimitChain.getBlacklistHandler().addToBlacklist(userId, reason);
        return ResponseEntity.ok(Dtos.ApiResponse.ok("Added " + userId + " to blacklist: " + reason));
    }

    @DeleteMapping("/chain/blacklist/{userId}")
    public ResponseEntity<Dtos.ApiResponse<String>> removeFromBlacklist(@PathVariable String userId) {
        rateLimitChain.getBlacklistHandler().removeFromBlacklist(userId);
        return ResponseEntity.ok(Dtos.ApiResponse.ok("Removed " + userId + " from blacklist"));
    }

    @GetMapping("/chain/blacklist")
    public ResponseEntity<Dtos.ApiResponse<?>> getBlacklist() {
        return ResponseEntity.ok(Dtos.ApiResponse.ok(rateLimitChain.getBlacklistHandler().getBlacklist()));
    }

    // ─── Command Pattern — Undo ───────────────────────────

    @PostMapping("/commands/undo")
    public ResponseEntity<Dtos.ApiResponse<String>> undo(
            @RequestParam(defaultValue = "admin") String actor) {
        boolean success = commandInvoker.undo(actor);
        return ResponseEntity.ok(success
                ? Dtos.ApiResponse.ok("Command undone successfully")
                : Dtos.ApiResponse.error("No commands to undo"));
    }

    @GetMapping("/commands/history-size")
    public ResponseEntity<Dtos.ApiResponse<Integer>> getCommandHistorySize() {
        return ResponseEntity.ok(Dtos.ApiResponse.ok(commandInvoker.historySize()));
    }
}