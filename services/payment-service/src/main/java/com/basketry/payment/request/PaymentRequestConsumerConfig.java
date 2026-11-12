package com.basketry.payment.request;

import com.basketry.payment.payment.PaymentRequestRejectedException;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
 * What happens to a request the listener could not handle (DESIGN.md Section
 * 5, payment-service requests).
 *
 * <ul>
 *   <li>A database failure says nothing about the request, so it is retried
 *       with no limit, by seeking back: an outage never drops a request. The
 *       offset is not committed meanwhile.</li>
 *   <li>Anything else, a rejected request or a bug, goes straight to the
 *       request topic's dead-letter topic, so one bad record never blocks its
 *       partition. If that publish fails, the record is tried again.</li>
 *   <li>The dead letter keeps the original value, byte for byte, so a replay
 *       is the same request. Its exception headers are only ours: the class
 *       and a message that never quotes a value. Spring's message, cause and
 *       stack-trace headers are not written, because a Jackson message in a
 *       cause can quote the card number.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "basketry.payment-requests.consumer.enabled", havingValue = "true")
class PaymentRequestConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentRequestConsumerConfig.class);
    static final String UNEXPECTED = "Unexpected error; see the payment-service log for this record";

    /** Read by {@link KafkaAdmin#initialize()}, on first use: startup never touches Kafka. */
    @Bean
    NewTopic chargeDeadLetterTopic(PaymentRequestProperties properties) {
        return deadLetterTopic(properties.chargeTopic(), properties);
    }

    @Bean
    NewTopic refundDeadLetterTopic(PaymentRequestProperties properties) {
        return deadLetterTopic(properties.refundTopic(), properties);
    }

    private static NewTopic deadLetterTopic(String requestTopic, PaymentRequestProperties properties) {
        return TopicBuilder.name(requestTopic + PaymentRequestProperties.DEAD_LETTER_SUFFIX)
                .partitions(properties.deadLetterPartitions())
                .replicas(properties.replicationFactor())
                .build();
    }

    /** Picked up by Spring Boot's listener container factory, for both listeners. */
    @Bean
    DefaultErrorHandler paymentRequestErrorHandler(KafkaTemplate<String, String> kafka,
                                                   KafkaAdmin admin,
                                                   PaymentRequestProperties properties) {
        // Partition -1: the producer chooses by key, so one order's dead letters stay in order.
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(kafka,
                (record, failure) -> new TopicPartition(deadLetterTopicOf(record), -1));
        publisher.setExceptionHeadersCreator((headers, failure, isKey, names) -> {
            headers.add(names.getExceptionInfo().getExceptionFqcn(),
                    reported(failure).getClass().getName().getBytes(StandardCharsets.UTF_8));
            headers.add(names.getExceptionInfo().getExceptionMessage(),
                    safeMessage(failure).getBytes(StandardCharsets.UTF_8));
        });
        ConsumerAwareRecordRecoverer recoverer = (record, consumer, failure) -> {
            Throwable reported = reported(failure);
            if (reported instanceof PaymentRequestRejectedException) {
                // No stack trace: the message is the whole story, and is safe to log.
                log.error("Sending record {}-{}@{} to {}: {}: {}", record.topic(), record.partition(),
                        record.offset(), deadLetterTopicOf(record), reported.getClass().getSimpleName(),
                        reported.getMessage());
            } else {
                log.error("Sending record {}-{}@{} to {} after an unexpected error", record.topic(),
                        record.partition(), record.offset(), deadLetterTopicOf(record), reported);
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

    private static String deadLetterTopicOf(ConsumerRecord<?, ?> record) {
        return record.topic() + PaymentRequestProperties.DEAD_LETTER_SUFFIX;
    }

    private static BackOff unlimited(PaymentRequestProperties properties) {
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

    /**
     * A rejection's own message, which by construction never quotes a value.
     * Any other message is not ours to vouch for, so it is replaced.
     */
    static String safeMessage(Throwable failure) {
        return reported(failure) instanceof PaymentRequestRejectedException rejected
                ? rejected.getMessage()
                : UNEXPECTED;
    }
}
