package com.basketry.inventory.outbox;

import static com.basketry.inventory.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.basketry.inventory.support.InventoryServiceTestBase;
import com.basketry.inventory.support.TopicReader;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

/**
 * The scheduled relay against a real broker (embedded, so no Docker). Like the
 * Compose broker, this one does not create topics on its own, so every result
 * topic exists only because the service created it.
 */
@EmbeddedKafka(
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = {
        "basketry.outbox.relay.enabled=true",
        "basketry.outbox.relay.interval=50ms",
        "basketry.outbox.relay.backoff-initial=50ms",
        "basketry.outbox.relay.backoff-max=200ms"})
class KafkaRelayTests extends InventoryServiceTestBase {

    private static final String RESERVED = StockResultEvents.RESERVED_TOPIC;
    private static final String FAILED = StockResultEvents.RESERVATION_FAILED_TOPIC;
    private static final String RELEASED = StockResultEvents.RELEASED_TOPIC;

    @Autowired
    private EmbeddedKafkaBroker broker;

    private void awaitAllSent() {
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(outboxRows())
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT)));
    }

    /** Each row to its own topic, keyed by its own orderId (101 and 102 here), with the stored bytes. */
    @Test
    void eachResultReachesItsTopicKeyedByOrderIdWithTheStoredBytes() {
        givenStock(42, 10);
        stock.reserve(reserve(101, 42, 3));
        stock.reserve(reserve(102, 42, 1001));
        stock.release(release(101, 42, 3));
        List<OutboxEvent> rows = outboxRows();
        awaitAllSent();

        ConsumerRecord<String, String> reserved = TopicReader.readKey(broker, RESERVED, "101", 1).getFirst();
        ConsumerRecord<String, String> failed = TopicReader.readKey(broker, FAILED, "102", 1).getFirst();
        ConsumerRecord<String, String> released = TopicReader.readKey(broker, RELEASED, "101", 1).getFirst();
        assertThat(reserved.value()).isEqualTo(rows.get(0).getPayload());
        assertThat(failed.value()).isEqualTo(rows.get(1).getPayload());
        assertThat(released.value()).isEqualTo(rows.get(2).getPayload());
        assertThat(reserved.headers().toArray()).isEmpty();
    }

    /** Only the three result topics: the request topics are order-service's. */
    @Test
    void theServiceCreatedItsResultTopicsWithThreePartitions() throws Exception {
        givenStock(42, 10);
        stock.reserve(reserve(103, 42, 1));
        awaitAllSent();

        try (KafkaConsumer<String, String> consumer = TopicReader.consumer(broker)) {
            for (String topic : List.of(RESERVED, FAILED, RELEASED)) {
                assertThat(consumer.partitionsFor(topic)).hasSize(3);
            }
            assertThat(consumer.listTopics().keySet()).doesNotContain(
                    "order.stock-reserve.requested", "order.stock-release.requested");
        }
    }
}
