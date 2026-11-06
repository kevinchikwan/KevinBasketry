package com.basketry.merchant.outbox;

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
 * Publishes to the status topic. The value is already JSON, stored in the
 * outbox, so it goes out as a plain string and carries no Java type header.
 *
 * <p>The service creates its own topic, here and not at startup, so startup
 * never waits for Kafka and the service can start first. The check runs again
 * after a failure, which also recreates the topic if the broker lost it, for
 * example after {@code docker compose down}. Only the relay's single thread
 * calls this class.
 */
@Component
class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final KafkaAdmin admin;
    private final OutboxProperties properties;
    private boolean topicReady;

    KafkaEventPublisher(KafkaTemplate<String, String> kafka, KafkaAdmin admin, OutboxProperties properties) {
        this.kafka = kafka;
        this.admin = admin;
        this.properties = properties;
    }

    @Override
    public void publish(String key, String payload) {
        if (!topicReady && !admin.initialize()) {
            throw new IllegalStateException("Kafka is not reachable: could not check the topic");
        }
        topicReady = true;
        try {
            kafka.send(properties.topic(), key, payload)
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            topicReady = false;
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing", interrupted);
        } catch (ExecutionException | TimeoutException failed) {
            topicReady = false;
            throw new IllegalStateException("Publish failed: " + failed.getMessage(), failed);
        } catch (RuntimeException failed) {
            topicReady = false;
            throw failed;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Topics {

        /** Read by {@link KafkaAdmin#initialize()}. */
        @Bean
        NewTopic merchantStatusTopic(OutboxProperties properties) {
            return TopicBuilder.name(properties.topic())
                    .partitions(properties.partitions())
                    .replicas(properties.replicationFactor())
                    .build();
        }
    }
}
