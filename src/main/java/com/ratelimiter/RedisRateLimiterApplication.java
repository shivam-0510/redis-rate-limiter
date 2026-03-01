package com.ratelimiter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RedisRateLimiterApplication {

	public static void main(String[] args) {
		SpringApplication.run(RedisRateLimiterApplication.class, args);
	}

}
