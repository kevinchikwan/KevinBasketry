package com.basketry.order.results;

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
 * What happens to a result the listener could not apply (DESIGN.md Section 5,
 * order-service results), as payment-service's request listeners:
 *
 * <ul>
 *   <li>A database failure, a row-lock timeout included, says nothing about
 *       the result, so it is retried with no limit, by seeking back. The offset
 *       is not committed meanwhile.</li>
 *   <li>Anything else, a malformed result or a bug, goes straight to the
 *       topic's own dead-letter topic ({@code <topic>.order-service.DLT}), so
 *       one bad record never blocks its partition. If that publish fails, the
 *       record is tried again. A dead-lettered result leaves its leg as if it
 *       never arrived: the deadline or the resends deal with it.</li>
 *   <li>The dead letter keeps the original value byte for byte. Its exception
 *       headers are only ours: the class, and a message that never quotes a
 *       value.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "basketry.order-results.consumer.enabled", havingValue = "true")
class ResultConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(ResultConsumerConfig.class);
    static final String UNEXPECTED = "Unexpected error; see the order-service log for this record";

    /** Read by KafkaAdmin.initialize(), on first use: startup never touches Kafka. */
    @Bean
    NewTopic chargedDeadLetterTopic(ResultProperties properties) {
        return deadLetterTopic(ResultTopics.CHARGED, properties);
    }

    @Bean
    NewTopic declinedDeadLetterTopic(ResultProperties properties) {
        return deadLetterTopic(ResultTopics.DECLINED, properties);
    }

    @Bean
    NewTopic refundedDeadLetterTopic(ResultProperties properties) {
        return deadLetterTopic(ResultTopics.REFUNDED, properties);
    }

    @Bean
    NewTopic reservedDeadLetterTopic(ResultProperties properties) {
        return deadLetterTopic(ResultTopics.RESERVED, properties);
    }

    @Bean
    NewTopic reservationFailedDeadLetterTopic(ResultProperties properties) {
        return deadLetterTopic(ResultTopics.RESERVATION_FAILED, properties);
    }

    @Bean
    NewTopic releasedDeadLetterTopic(ResultProperties properties) {
        return deadLetterTopic(ResultTopics.RELEASED, properties);
    }

    private static NewTopic deadLetterTopic(String resultTopic, ResultProperties properties) {
        return TopicBuilder.name(ResultTopics.deadLetterTopicOf(resultTopic))
                .partitions(properties.deadLetterPartitions())
                .replicas(properties.replicationFactor())
                .build();
    }

    /** Picked up by Spring Boot's listener container factory, for all six listeners. */
    @Bean
    DefaultErrorHandler resultErrorHandler(KafkaTemplate<String, String> kafka, KafkaAdmin admin,
                                           ResultProperties properties) {
        // Partition -1: the producer chooses by key, so one order's dead letters stay in order.
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(kafka,
                (record, failure) -> new TopicPartition(ResultTopics.deadLetterTopicOf(record.topic()), -1));
        publisher.setExceptionHeadersCreator((headers, failure, isKey, names) -> {
            headers.add(names.getExceptionInfo().getExceptionFqcn(),
                    reported(failure).getClass().getName().getBytes(StandardCharsets.UTF_8));
            headers.add(names.getExceptionInfo().getExceptionMessage(),
                    safeMessage(failure).getBytes(StandardCharsets.UTF_8));
        });
        ConsumerAwareRecordRecoverer recoverer = (record, consumer, failure) -> {
            Throwable reported = reported(failure);
            if (reported instanceof InvalidResultException) {
                log.error("Sending record {}-{}@{} to {}: {}", record.topic(), record.partition(), record.offset(),
                        ResultTopics.deadLetterTopicOf(record.topic()), reported.getMessage());
            } else {
                log.error("Sending record {}-{}@{} to {} after an unexpected error", record.topic(),
                        record.partition(), record.offset(), ResultTopics.deadLetterTopicOf(record.topic()), reported);
            }
            if (!admin.initialize()) {
                throw new IllegalStateException("Kafka is not reachable: could not check the dead-letter topics");
            }
            publisher.accept(record, consumer, failure);
        };
        // Not the other way round: with a default of no retries, the handler never calls the function.
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, unlimited(properties));
        handler.setBackOffFunction((record, failure) -> isDatabaseFailure(failure) ? null : new FixedBackOff(0, 0));
        return handler;
    }

    private static BackOff unlimited(ResultProperties properties) {
        ExponentialBackOff backOff = new ExponentialBackOff(properties.retryInitial().toMillis(), 2);
        backOff.setMaxInterval(properties.retryMax().toMillis());
        return backOff;
    }

    /** The whole cause chain is searched: a library can wrap a database failure. */
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

    /** A parse failure's own message, which never quotes a value; anything else is replaced. */
    static String safeMessage(Throwable failure) {
        return reported(failure) instanceof InvalidResultException invalid ? invalid.getMessage() : UNEXPECTED;
    }
}
