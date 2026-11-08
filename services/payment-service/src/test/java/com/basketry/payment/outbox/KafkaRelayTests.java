package com.basketry.payment.outbox;

import static com.basketry.payment.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.basketry.payment.payment.ChargeOutcome.Charged;
import com.basketry.payment.payment.RefundCommand;
import com.basketry.payment.support.PaymentServiceTestBase;
import com.basketry.payment.support.TopicReader;
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
class KafkaRelayTests extends PaymentServiceTestBase {

    private static final String CHARGED = PaymentResultEvents.CHARGED_TOPIC;
    private static final String DECLINED = PaymentResultEvents.DECLINED_TOPIC;
    private static final String REFUNDED = PaymentResultEvents.REFUNDED_TOPIC;

    @Autowired
    private EmbeddedKafkaBroker broker;

    private void awaitAllSent() {
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(outboxRows())
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT)));
    }

    @Test
    void eachResultReachesItsTopicKeyedByOrderIdWithTheStoredBytes() {
        long order = 101;
        Charged charged = (Charged) payments.authorize(charge(order, 3, "25.50", APPROVED_CARD));
        payments.authorize(charge(order, 4, "10.00", EXPIRED_CARD));
        payments.refund(new RefundCommand(order, 3, charged.transactionRef()));
        List<OutboxEvent> rows = outboxRows();
        awaitAllSent();

        String key = Long.toString(order);
        ConsumerRecord<String, String> chargedRecord = TopicReader.readKey(broker, CHARGED, key, 1).getFirst();
        ConsumerRecord<String, String> declinedRecord = TopicReader.readKey(broker, DECLINED, key, 1).getFirst();
        ConsumerRecord<String, String> refundedRecord = TopicReader.readKey(broker, REFUNDED, key, 1).getFirst();
        assertThat(chargedRecord.value()).isEqualTo(rows.get(0).getPayload());
        assertThat(declinedRecord.value()).isEqualTo(rows.get(1).getPayload());
        assertThat(refundedRecord.value()).isEqualTo(rows.get(2).getPayload());
        assertThat(chargedRecord.headers().toArray()).isEmpty();
    }

    /** Only the three result topics: the request topics are order-service's. */
    @Test
    void theServiceCreatedItsResultTopicsWithThreePartitions() throws Exception {
        payments.authorize(charge(102, 3, "25.50", APPROVED_CARD));
        awaitAllSent();

        try (KafkaConsumer<String, String> consumer = TopicReader.consumer(broker)) {
            for (String topic : List.of(CHARGED, DECLINED, REFUNDED)) {
                assertThat(consumer.partitionsFor(topic)).hasSize(3);
            }
            assertThat(consumer.listTopics().keySet()).doesNotContain(
                    "order.merchant-charge.requested", "order.merchant-refund.requested");
        }
    }
}
