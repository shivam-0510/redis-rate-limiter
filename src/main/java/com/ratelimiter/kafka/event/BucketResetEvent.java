package com.ratelimiter.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Fired when a token bucket is reset.
 * Used to invalidate caches in other instances.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class BucketResetEvent extends BaseEvent {

    private String userId;
    private String resetBy;
    private String reason;
    private long tokensBeforeReset;
    private long capacityAfterReset;
}