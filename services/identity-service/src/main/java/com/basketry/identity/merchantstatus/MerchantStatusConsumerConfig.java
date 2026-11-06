package com.basketry.identity.merchantstatus;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.TransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * What happens to a record the listener could not apply (DESIGN.md Section 5).
 *
 * <ul>
 *   <li>A database failure says nothing about the record, so it is retried
 *       with no limit, as the relay does: an outage never drops an event. The
 *       offset is not committed meanwhile.</li>
 *   <li>Anything else goes straight to the dead-letter topic, so one bad record
 *       never blocks its partition. It is kept rather than skipped, because
 *       skipping could lose a deactivation for good.</li>
 *   <li>If the dead-letter publish fails, the record is tried again, not
 *       dropped.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "basketry.merchant-status.consumer.enabled", havingValue = "true")
class MerchantStatusConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(MerchantStatusConsumerConfig.class);

    /** Read by {@link KafkaAdmin#initialize()}, on first use: startup never touches Kafka. */
    @Bean
    NewTopic merchantStatusDeadLetterTopic(MerchantStatusProperties properties) {
        return TopicBuilder.name(properties.deadLetterTopic())
                .partitions(properties.deadLetterPartitions())
                .replicas(properties.replicationFactor())
                .build();
    }

    /** Picked up by Spring Boot's listener container factory. */
    @Bean
    DefaultErrorHandler merchantStatusErrorHandler(KafkaTemplate<String, String> kafka,
                                                   KafkaAdmin admin,
                                                   MerchantStatusProperties properties) {
        // Partition -1: the producer chooses by key, so one merchant's dead letters stay in order.
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(kafka,
                (record, failure) -> new TopicPartition(properties.deadLetterTopic(), -1));
        ConsumerAwareRecordRecoverer recoverer = (record, consumer, failure) -> {
            log.error("Sending record {}-{}@{} to {}: {}", record.topic(), record.partition(), record.offset(),
                    properties.deadLetterTopic(), failure.toString());
            if (!admin.initialize()) {
                throw new IllegalStateException("Kafka is not reachable: could not check the dead-letter topic");
            }
            publisher.accept(record, consumer, failure);
        };
        // Not the other way round: with a default of no retries, the handler never calls the function.
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, unlimited(properties));
        handler.setBackOffFunction((record, failure) -> isDatabaseFailure(failure) ? null : new FixedBackOff(0, 0));
        return handler;
    }

    private static BackOff unlimited(MerchantStatusProperties properties) {
        ExponentialBackOff backOff = new ExponentialBackOff(properties.retryInitial().toMillis(), 2);
        backOff.setMaxInterval(properties.retryMax().toMillis());
        return backOff;
    }

    /** The container wraps what the listener threw, so the whole chain is searched. */
    private static boolean isDatabaseFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessException || cause instanceof TransactionException) {
                return true;
            }
        }
        return false;
    }
}
