package com.basketry.catalog.merchantstatus;

import java.nio.charset.StandardCharsets;
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
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.TransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * What happens to a record the listener could not apply (DESIGN.md Section 5,
 * the catalog-service consumer), as in identity-service, with inventory-service's
 * dead-letter headers:
 *
 * <ul>
 *   <li>A database failure says nothing about the record, so it is retried with
 *       no limit, by seeking back: an outage never drops an event. The offset
 *       is not committed meanwhile. That includes a primary-key violation of
 *       two instances inserting the same new merchant at once: the retry then
 *       takes the MERGE's MATCHED branch.</li>
 *   <li>Anything else goes straight to catalog-service's own dead-letter topic,
 *       so one bad record never blocks its partition. It is kept rather than
 *       skipped, because skipping could lose a deactivation for good. If that
 *       publish fails, the record is tried again.</li>
 *   <li>Redis never gets here: ProductListCacheGuard turns every Redis failure
 *       into "unavailable", and ListingInvalidator logs a WARN.</li>
 *   <li>The dead letter keeps the original value, byte for byte. Its exception
 *       headers are only ours: the class and a message that never quotes a
 *       value.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "basketry.merchant-status.consumer.enabled", havingValue = "true")
class MerchantStatusConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(MerchantStatusConsumerConfig.class);
    static final String UNEXPECTED = "Unexpected error; see the catalog-service log for this record";

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
        publisher.setExceptionHeadersCreator((headers, failure, isKey, names) -> {
            headers.add(names.getExceptionInfo().getExceptionFqcn(),
                    reported(failure).getClass().getName().getBytes(StandardCharsets.UTF_8));
            headers.add(names.getExceptionInfo().getExceptionMessage(),
                    safeMessage(failure).getBytes(StandardCharsets.UTF_8));
        });
        ConsumerAwareRecordRecoverer recoverer = (record, consumer, failure) -> {
            Throwable reported = reported(failure);
            if (reported instanceof InvalidMerchantStatusEventException) {
                // No stack trace: the message is the whole story, and is safe to log.
                log.error("Sending record {}-{}@{} to {}: {}", record.topic(), record.partition(),
                        record.offset(), properties.deadLetterTopic(), reported.getMessage());
            } else {
                log.error("Sending record {}-{}@{} to {} after an unexpected error", record.topic(),
                        record.partition(), record.offset(), properties.deadLetterTopic(), reported);
            }
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

    /** A database failure can arrive wrapped (by a library or the container), so the whole chain is searched. */
    static boolean isDatabaseFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessException || cause instanceof TransactionException) {
                return true;
            }
        }
        return false;
    }

    /** The exception the listener threw, without the container's wrapper. */
    static Throwable reported(Throwable failure) {
        Throwable reported = failure;
        while (reported instanceof ListenerExecutionFailedException && reported.getCause() != null) {
            reported = reported.getCause();
        }
        return reported;
    }

    /**
     * A refusal's own message, which by construction never quotes a value. Any
     * other message is not ours to vouch for, so it is replaced.
     */
    static String safeMessage(Throwable failure) {
        return reported(failure) instanceof InvalidMerchantStatusEventException invalid
                ? invalid.getMessage()
                : UNEXPECTED;
    }
}
