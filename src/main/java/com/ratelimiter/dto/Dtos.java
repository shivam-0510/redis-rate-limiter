package com.ratelimiter.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

public class Dtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RateLimitRequest {
        @NotBlank(message = "userId is required")
        private String userId;
        private int tokens = 1;  // tokens to consume
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RateLimitResponse {
        private boolean allowed;
        private long tokensRemaining;
        private long capacity;
        private long refillRate;
        private long retryAfterSeconds;
        private String userId;
        private long requestTimestamp;
        private String instanceId;
        private String message;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PolicyUpdateRequest {
        @NotBlank
        private String userId;
        @Min(1)
        private long capacity;
        @Min(1)
        private long refillRate;
        @Min(1)
        private long refillPeriodSeconds;
        private String tier;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BucketStatusResponse {
        private String userId;
        private long tokens;
        private long capacity;
        private long refillRate;
        private long refillPeriodSeconds;
        private String tier;
        private double utilizationPercent;
        private boolean active;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SystemStatsResponse {
        private long totalRequests;
        private long allowedRequests;
        private long blockedRequests;
        private double blockRate;
        private int activeBuckets;
        private String redisStatus;
        private String instanceId;
        private long uptimeSeconds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SimulateRequest {
        @NotBlank
        private String userId;
        private int requestCount = 10;
        private int delayMs = 100;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SimulateResult {
        private String userId;
        private int totalRequests;
        private int allowedCount;
        private int blockedCount;
        private double allowRate;
        private java.util.List<RateLimitResponse> results;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApiResponse<T> {
        private boolean success;
        private String message;
        private T data;
        private long timestamp;

        public static <T> ApiResponse<T> ok(T data) {
            return ApiResponse.<T>builder()
                    .success(true).data(data)
                    .timestamp(System.currentTimeMillis()).build();
        }

        public static <T> ApiResponse<T> ok(String message, T data) {
            return ApiResponse.<T>builder()
                    .success(true).message(message).data(data)
                    .timestamp(System.currentTimeMillis()).build();
        }

        public static <T> ApiResponse<T> error(String message) {
            return ApiResponse.<T>builder()
                    .success(false).message(message)
                    .timestamp(System.currentTimeMillis()).build();
        }
    }
}