package com.ratelimiter.kafka.consumer;

import com.ratelimiter.kafka.event.BaseEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dead Letter Topic (DLT) Consumer.
 *
 * Processes messages that failed all retry attempts.
 * In production: persist to DB, alert engineers, provide replay API.
 */
@Slf4j
@Component
public class DeadLetterConsumer {

    private final List<DeadLetterEntry> deadLetters =
            Collections.synchronizedList(new ArrayList<>());

    @KafkaListener(
            topics = "${kafka.topics.dead-letter:rate-limit-events.DLT}",
            groupId = "rate-limiter-dlq-handler",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeDeadLetter(
            ConsumerRecord<String, Object> record,
            Acknowledgment ack,
            @Header(value = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String exceptionMessage,
            @Header(value = KafkaHeaders.ORIGINAL_TOPIC, required = false) String originalTopic,
            @Header(value = KafkaHeaders.ORIGINAL_PARTITION, required = false) Integer originalPartition,
            @Header(value = KafkaHeaders.ORIGINAL_OFFSET, required = false) Long originalOffset) {

        log.error("DLT message received — originalTopic={} partition={} offset={} error={}",
                originalTopic, originalPartition, originalOffset, exceptionMessage);

        DeadLetterEntry entry = new DeadLetterEntry(
                record.key(),
                record.value(),
                originalTopic,
                originalPartition,
                originalOffset,
                exceptionMessage,
                System.currentTimeMillis()
        );

        deadLetters.add(entry);
        if (deadLetters.size() > 200) deadLetters.remove(0);

        // TODO: In production: persist to DB for manual replay
        // TODO: Alert engineering team
        // TODO: Expose replay endpoint

        ack.acknowledge();
    }

    public List<DeadLetterEntry> getDeadLetters() {
        return List.copyOf(deadLetters);
    }

    public int getCount() { return deadLetters.size(); }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class DeadLetterEntry {
        private String key;
        private Object payload;
        private String originalTopic;
        private Integer originalPartition;
        private Long originalOffset;
        private String errorMessage;
        private long receivedAt;
    }
}