package com.ratelimiter.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.List;

@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isBlank()) {
            config.setPassword(password);
        }
        return new LettuceConnectionFactory(config);
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * Token Bucket Lua Script for atomic execution.
     *
     * This script atomically:
     * 1. Loads current bucket state from Redis
     * 2. Calculates token refill based on elapsed time
     * 3. Checks if enough tokens are available
     * 4. Deducts tokens if allowed
     * 5. Saves updated state back to Redis
     *
     * Returns: [allowed(0/1), tokensRemaining, retryAfterSeconds]
     */
    @Bean
    public DefaultRedisScript<List> tokenBucketScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptText("""
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local refill_period = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])
            local now = tonumber(ARGV[5])
            local ttl = tonumber(ARGV[6])
            
            -- Load current state
            local data = redis.call('HMGET', key, 'tokens', 'last_refill')
            local tokens = tonumber(data[1])
            local last_refill = tonumber(data[2])
            
            -- Initialize bucket if it doesn't exist
            if tokens == nil then
                tokens = capacity
                last_refill = now
            end
            
            -- Calculate tokens to add based on elapsed time
            local elapsed = now - last_refill
            local periods_elapsed = math.floor(elapsed / refill_period)
            local tokens_to_add = periods_elapsed * refill_rate
            
            -- Refill tokens (cap at capacity)
            if tokens_to_add > 0 then
                tokens = math.min(capacity, tokens + tokens_to_add)
                last_refill = last_refill + (periods_elapsed * refill_period)
            end
            
            -- Check if request is allowed
            local allowed = 0
            local retry_after = 0
            
            if tokens >= requested then
                tokens = tokens - requested
                allowed = 1
            else
                -- Calculate when enough tokens will be available
                local tokens_needed = requested - tokens
                local periods_needed = math.ceil(tokens_needed / refill_rate)
                retry_after = periods_needed * refill_period
            end
            
            -- Save updated state with TTL
            redis.call('HMSET', key, 'tokens', tokens, 'last_refill', last_refill)
            redis.call('EXPIRE', key, ttl)
            
            return {allowed, tokens, retry_after, capacity}
        """);
        script.setResultType(List.class);
        return script;
    }
}
