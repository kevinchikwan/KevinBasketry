package com.basketry.inventory.outbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * When the publisher checks the topics: before its first publish and again
 * after any failure, so a topic the broker lost is created again. A real broker
 * cannot show this here: deleting a topic on the embedded broker takes its log
 * directory offline on Windows.
 */
class KafkaResultPublisherTests {

    private static final String TOPIC = StockResultEvents.RESERVED_TOPIC;

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final KafkaAdmin admin = mock(KafkaAdmin.class);
    private final KafkaResultPublisher publisher = new KafkaResultPublisher(kafka, admin,
            new OutboxProperties(3, (short) 1, Duration.ofSeconds(1), null));

    private static CompletableFuture<SendResult<String, String>> sent() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, String>> failed() {
        return CompletableFuture.failedFuture(new KafkaException("topic missing"));
    }

    @Test
    void theTopicsAreCheckedOnceAndAgainAfterAFailure() {
        when(admin.initialize()).thenReturn(true);
        when(kafka.send(TOPIC, "7", "a")).thenReturn(sent(), sent(), failed(), sent());

        publisher.publish(TOPIC, "7", "a");
        publisher.publish(TOPIC, "7", "a");   // no second check while all is well
        verify(admin, times(1)).initialize();

        assertThatThrownBy(() -> publisher.publish(TOPIC, "7", "a")).hasMessageContaining("Publish failed");
        publisher.publish(TOPIC, "7", "a");   // checked again, which recreates a lost topic

        verify(admin, times(2)).initialize();
    }

    @Test
    void nothingIsSentWhileTheTopicsCannotBeChecked() {
        when(admin.initialize()).thenReturn(false);

        assertThatThrownBy(() -> publisher.publish(TOPIC, "7", "a"))
                .hasMessage("Kafka is not reachable: could not check the topics");

        verify(kafka, times(0)).send(TOPIC, "7", "a");
    }
}
