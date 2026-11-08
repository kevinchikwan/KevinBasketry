package com.basketry.payment.outbox;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes a result to the topic its outbox row names. The value is already
 * JSON, stored in the row, so it goes out as a plain string and carries no
 * Java type header.
 *
 * <p>The service creates its own topics, here and not at startup, so startup
 * never waits for Kafka. {@link KafkaAdmin#initialize()} creates every topic
 * this service declares at once: the three result topics, and the two
 * dead-letter topics when the listeners are on. The check runs again after any
 * failure, which also recreates a topic the broker lost, for example after
 * {@code docker compose down -v} with the service still running. Only the
 * relay's single thread calls this class.
 */
@Component
class KafkaResultPublisher implements ResultPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final KafkaAdmin admin;
    private final OutboxProperties properties;
    private boolean topicsReady;

    KafkaResultPublisher(KafkaTemplate<String, String> kafka, KafkaAdmin admin, OutboxProperties properties) {
        this.kafka = kafka;
        this.admin = admin;
        this.properties = properties;
    }

    @Override
    public void publish(String topic, String key, String payload) {
        if (!topicsReady && !admin.initialize()) {
            throw new IllegalStateException("Kafka is not reachable: could not check the topics");
        }
        topicsReady = true;
        try {
            kafka.send(topic, key, payload).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            topicsReady = false;
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing", interrupted);
        } catch (ExecutionException | TimeoutException failed) {
            topicsReady = false;
            throw new IllegalStateException("Publish failed: " + failed.getMessage(), failed);
        } catch (RuntimeException failed) {
            topicsReady = false;
            throw failed;
        }
    }

    /** Read by {@link KafkaAdmin#initialize()}. The request topics are order-service's and are not here. */
    @Configuration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic chargedTopic(OutboxProperties properties) {
            return resultTopic(PaymentResultEvents.CHARGED_TOPIC, properties);
        }

        @Bean
        NewTopic declinedTopic(OutboxProperties properties) {
            return resultTopic(PaymentResultEvents.DECLINED_TOPIC, properties);
        }

        @Bean
        NewTopic refundedTopic(OutboxProperties properties) {
            return resultTopic(PaymentResultEvents.REFUNDED_TOPIC, properties);
        }

        private static NewTopic resultTopic(String name, OutboxProperties properties) {
            return TopicBuilder.name(name)
                    .partitions(properties.partitions())
                    .replicas(properties.replicationFactor())
                    .build();
        }
    }
}
