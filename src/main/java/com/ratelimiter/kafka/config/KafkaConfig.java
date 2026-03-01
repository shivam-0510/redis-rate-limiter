package com.ratelimiter.kafka.config;

import com.ratelimiter.kafka.event.BaseEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Complete Kafka configuration.
 * - 5 topics with correct partition / replication settings
 * - Idempotent exactly-once producer
 * - Manual-ack consumer with exponential retry backoff
 * - Dead Letter Topic (DLT) for failed messages
 */
@Slf4j
@EnableKafka
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${kafka.topics.partitions:3}")
    private int partitions;

    @Value("${kafka.topics.replication-factor:1}")
    private short replicationFactor;

    // ─── Topic Names ──────────────────────────────────────

    @Value("${kafka.topics.rate-limit-events:rate-limit-events}")
    private String rateLimitEventsTopic;

    @Value("${kafka.topics.rate-limit-alerts:rate-limit-alerts}")
    private String rateLimitAlertsTopic;

    @Value("${kafka.topics.audit-log:audit-log}")
    private String auditLogTopic;

    @Value("${kafka.topics.policy-changes:policy-changes}")
    private String policyChangesTopic;

    @Value("${kafka.topics.metrics-aggregation:metrics-aggregation}")
    private String metricsAggregationTopic;

    @Value("${kafka.topics.dead-letter:rate-limit-events.DLT}")
    private String deadLetterTopic;

    // ─── Topics ───────────────────────────────────────────

    @Bean
    public NewTopic rateLimitEventsTopic() {
        return TopicBuilder.name(rateLimitEventsTopic)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config("retention.ms", "604800000")       // 7 days
                .config("compression.type", "snappy")
                .config("min.insync.replicas", "1")
                .build();
    }

    @Bean
    public NewTopic rateLimitAlertsTopic() {
        return TopicBuilder.name(rateLimitAlertsTopic)
                .partitions(1)
                .replicas(replicationFactor)
                .config("retention.ms", "2592000000")      // 30 days
                .build();
    }

    @Bean
    public NewTopic auditLogTopic() {
        return TopicBuilder.name(auditLogTopic)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config("retention.ms", "-1")              // infinite retention
                .config("cleanup.policy", "compact")
                .build();
    }

    @Bean
    public NewTopic policyChangesTopic() {
        return TopicBuilder.name(policyChangesTopic)
                .partitions(1)
                .replicas(replicationFactor)
                .config("retention.ms", "2592000000")
                .build();
    }

    @Bean
    public NewTopic metricsAggregationTopic() {
        return TopicBuilder.name(metricsAggregationTopic)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config("retention.ms", "86400000")        // 1 day
                .build();
    }

    @Bean
    public NewTopic deadLetterTopic() {
        return TopicBuilder.name(deadLetterTopic)
                .partitions(1)
                .replicas(replicationFactor)
                .config("retention.ms", "2592000000")
                .build();
    }

    // ─── Producer ─────────────────────────────────────────

    @Bean
    public ProducerFactory<String, BaseEvent> producerFactory() {

        Map<String, Object> props = new HashMap<>();

        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // Exactly-once delivery
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);

        // Throughput tuning
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

        return new DefaultKafkaProducerFactory<>(
                props,
                new StringSerializer(),
                new JacksonJsonSerializer<>()
        );
    }

    @Bean
    public KafkaTemplate<String, BaseEvent> kafkaTemplate() {
        KafkaTemplate<String, BaseEvent> template = new KafkaTemplate<>(producerFactory());
        template.setObservationEnabled(true);
        return template;
    }

    // ─── Consumer ─────────────────────────────────────────

    @Bean
    public ConsumerFactory<String, BaseEvent> consumerFactory() {

        Map<String, Object> props = new HashMap<>();

        props.put(org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG, "rate-limiter-group");
        props.put(org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(org.apache.kafka.clients.consumer.ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(org.apache.kafka.clients.consumer.ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        JacksonJsonDeserializer<BaseEvent> deserializer =
                new JacksonJsonDeserializer<>(BaseEvent.class);

        deserializer.addTrustedPackages("com.ratelimiter.redis_rate_limiter.kafka.event");
        deserializer.setRemoveTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(
                props,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                deserializer
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BaseEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, BaseEvent> consumerFactory,
            KafkaTemplate<String, BaseEvent> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, BaseEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(3);

        factory.getContainerProperties()
                .setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // ✅ Observation moved here in newer versions
        factory.getContainerProperties()
                .setObservationEnabled(true);

        // Exponential backoff retry + Dead Letter Topic
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(30000L);
        backOff.setMaxElapsedTime(120000L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(kafkaTemplate),
                backOff
        );

        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("Retry attempt {} for record offset={} due to: {}",
                        deliveryAttempt, record.offset(), ex.getMessage())
        );

        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}