package com.basketry.order.outbox;

import static com.basketry.order.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.basketry.order.support.OrderServiceTestBase;
import com.basketry.order.support.TopicReader;
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
 * Compose broker, this one does not create topics on its own, so every topic
 * exists only because the service created it.
 */
@EmbeddedKafka(
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = {
        "basketry.outbox.relay.enabled=true",
        "basketry.outbox.relay.interval=50ms",
        "basketry.outbox.relay.backoff-initial=50ms",
        "basketry.outbox.relay.backoff-max=200ms"})
class KafkaRelayTests extends OrderServiceTestBase {

    @Autowired
    private EmbeddedKafkaBroker broker;

    private void awaitAllSent() {
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(outboxRows())
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT)));
    }

    /**
     * Each request reaches its topic keyed by orderId, with the bytes stored at
     * checkout (the charge with its card number) and no headers; the stored row
     * then keeps none. Mutation: the key left out of the send.
     */
    @Test
    void eachRequestReachesItsTopicWithTheStoredBytes() {
        givenBuyer();
        givenProduct(10, MERCHANT, "2.50");
        givenMerchant(MERCHANT, "5.00");
        long orderId = checkout(command("key-0001", "2.50", 10, 1)).order().getId();
        List<OutboxEvent> rows = outboxRows();
        awaitAllSent();

        String key = Long.toString(orderId);
        ConsumerRecord<String, String> charge =
                TopicReader.readKey(broker, OrderRequests.CHARGE_TOPIC, key, 1).getFirst();
        ConsumerRecord<String, String> reserve =
                TopicReader.readKey(broker, OrderRequests.RESERVE_TOPIC, key, 1).getFirst();
        assertThat(charge.value()).isEqualTo(rows.get(0).getPayload()).contains(CARD);
        assertThat(reserve.value()).isEqualTo(rows.get(1).getPayload());
        assertThat(charge.headers().toArray()).isEmpty();
        assertThat(outboxRows().getFirst().getPayload()).doesNotContain(CARD);
    }

    /**
     * The six topics order-service owns, created together with 3 partitions;
     * none of payment's or inventory's result topics. Mutation: one topic bean
     * left out (order.failed never created).
     */
    @Test
    void theServiceCreatedItsSixTopicsWithThreePartitions() {
        givenBuyer();
        givenProduct(10, MERCHANT, "2.50");
        givenMerchant(MERCHANT, "5.00");
        checkout(command("key-0002", "2.50", 10, 1));
        awaitAllSent();

        try (KafkaConsumer<String, String> consumer = TopicReader.consumer(broker)) {
            for (String topic : List.of(OrderRequests.CHARGE_TOPIC, OrderRequests.REFUND_TOPIC,
                    OrderRequests.RESERVE_TOPIC, OrderRequests.RELEASE_TOPIC, OrderRequests.SUCCEEDED_TOPIC,
                    OrderRequests.FAILED_TOPIC)) {
                assertThat(consumer.partitionsFor(topic)).as(topic).hasSize(3);
            }
            assertThat(consumer.listTopics().keySet()).doesNotContain(
                    "order.merchant.charged", "order.stock.reserved");
        }
    }
}
