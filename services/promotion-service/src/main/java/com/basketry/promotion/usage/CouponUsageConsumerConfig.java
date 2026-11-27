package com.basketry.promotion.usage;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
 * What happens to an order.succeeded record the listener could not count
 * (DESIGN.md Section 5, promotion-service), as order-service's result
 * listeners:
 *
 * <ul>
 *   <li>A database failure says nothing about the event, so it is retried with
 *       no limit, by seeking back. The offset is not committed meanwhile.</li>
 *   <li>Anything else, a malformed event, a tombstone, a value that is not
 *       UTF-8 or a bug, goes straight to the dead-letter topic
 *       ({@value OrderSucceededTopic#DEAD_LETTER}), so one bad record never
 *       blocks its partition. If that publish fails, the record is tried
 *       again.</li>
 *   <li>The dead letter keeps the original key and value byte for byte. Its
 *       exception headers are only ours: the class, and a message that never
 *       quotes a value.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "basketry.coupon-usage.consumer.enabled", havingValue = "true")
@EnableConfigurationProperties(CouponUsageProperties.class)
class CouponUsageConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(CouponUsageConsumerConfig.class);
    static final String UNEXPECTED = "Unexpected error; see the promotion-service log for this record";

    /** Read by KafkaAdmin.initialize(), on first use: startup never touches Kafka. */
    @Bean
    NewTopic orderSucceededDeadLetterTopic(CouponUsageProperties properties) {
        return TopicBuilder.name(OrderSucceededTopic.DEAD_LETTER)
                .partitions(properties.deadLetterPartitions())
                .replicas(properties.replicationFactor())
                .build();
    }

    /** Picked up by Spring Boot's listener container factory. */
    @Bean
    DefaultErrorHandler couponUsageErrorHandler(KafkaTemplate<byte[], byte[]> kafka, KafkaAdmin admin,
                                                CouponUsageProperties properties) {
        // Partition -1: the producer chooses by key, so one order's dead letters stay in order.
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(kafka,
                (record, failure) -> new TopicPartition(OrderSucceededTopic.DEAD_LETTER, -1));
        publisher.setExceptionHeadersCreator((headers, failure, isKey, names) -> {
            headers.add(names.getExceptionInfo().getExceptionFqcn(),
                    reported(failure).getClass().getName().getBytes(StandardCharsets.UTF_8));
            headers.add(names.getExceptionInfo().getExceptionMessage(),
                    safeMessage(failure).getBytes(StandardCharsets.UTF_8));
        });
        ConsumerAwareRecordRecoverer recoverer = (record, consumer, failure) -> {
            Throwable reported = reported(failure);
            if (reported instanceof InvalidOrderSucceededException) {
                log.error("Sending record {}-{}@{} to {}: {}", record.topic(), record.partition(), record.offset(),
                        OrderSucceededTopic.DEAD_LETTER, reported.getMessage());
            } else {
                log.error("Sending record {}-{}@{} to {} after an unexpected error", record.topic(),
                        record.partition(), record.offset(), OrderSucceededTopic.DEAD_LETTER, reported);
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

    private static BackOff unlimited(CouponUsageProperties properties) {
        ExponentialBackOff backOff = new ExponentialBackOff(properties.retryInitial().toMillis(), 2);
        backOff.setMaxInterval(properties.retryMax().toMillis());
        return backOff;
    }

    /**
     * The backoff function gets the listener's own exception, not a container
     * wrapper; a library can still wrap a database failure, so the whole chain
     * is searched.
     */
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

    /** A parse failure's or a refused row's own message, which never quotes a value; anything else is replaced. */
    static String safeMessage(Throwable failure) {
        Throwable reported = reported(failure);
        return reported instanceof InvalidOrderSucceededException || reported instanceof CouponUsageNotRecordedException
                ? reported.getMessage() : UNEXPECTED;
    }
}
