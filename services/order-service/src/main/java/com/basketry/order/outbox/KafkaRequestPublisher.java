package com.basketry.order.outbox;

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
 * Publishes a stored request or order event to the topic its outbox row names,
 * as payment-service's publisher. The value is already JSON, stored in the row,
 * so it goes out as a plain string and carries no Java type header.
 *
 * <p>order-service creates the six topics it owns (DESIGN.md Section 5): the
 * four request topics, order.succeeded and order.failed, all together on its
 * first publish and not at startup, so startup never waits for Kafka. The check
 * runs again after any failure, which also recreates a topic the broker lost.
 * Only the relay's single thread calls this class.
 */
@Component
class KafkaRequestPublisher implements RequestPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final KafkaAdmin admin;
    private final OutboxProperties properties;
    private boolean topicsReady;

    KafkaRequestPublisher(KafkaTemplate<String, String> kafka, KafkaAdmin admin, OutboxProperties properties) {
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

    /** Read by {@link KafkaAdmin#initialize()}. The result topics are payment's and inventory's, not here. */
    @Configuration(proxyBeanMethods = false)
    static class Topics {

        @Bean
        NewTopic chargeRequestTopic(OutboxProperties properties) {
            return topic(OrderRequests.CHARGE_TOPIC, properties);
        }

        @Bean
        NewTopic refundRequestTopic(OutboxProperties properties) {
            return topic(OrderRequests.REFUND_TOPIC, properties);
        }

        @Bean
        NewTopic reserveRequestTopic(OutboxProperties properties) {
            return topic(OrderRequests.RESERVE_TOPIC, properties);
        }

        @Bean
        NewTopic releaseRequestTopic(OutboxProperties properties) {
            return topic(OrderRequests.RELEASE_TOPIC, properties);
        }

        @Bean
        NewTopic orderSucceededTopic(OutboxProperties properties) {
            return topic(OrderRequests.SUCCEEDED_TOPIC, properties);
        }

        @Bean
        NewTopic orderFailedTopic(OutboxProperties properties) {
            return topic(OrderRequests.FAILED_TOPIC, properties);
        }

        private static NewTopic topic(String name, OutboxProperties properties) {
            return TopicBuilder.name(name)
                    .partitions(properties.partitions())
                    .replicas(properties.replicationFactor())
                    .build();
        }
    }
}
