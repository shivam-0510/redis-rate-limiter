package com.ratelimiter.redis_rate_limiter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RateLimiterController {
    @GetMapping("/rate-limit")
    public String testApi() {
        return "API Working!";
    }
}
