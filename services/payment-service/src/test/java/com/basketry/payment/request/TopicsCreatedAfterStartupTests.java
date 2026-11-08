package com.basketry.payment.request;

import static com.basketry.payment.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.basketry.payment.support.PaymentServiceTestBase;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

/**
 * The normal first run: order-service owns the request topics, so
 * payment-service may start before they exist. The listeners must start
 * anyway, find the topics by themselves and read them from the beginning.
 */
@EmbeddedKafka(
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = {
        "basketry.payment-requests.consumer.enabled=true",
        "spring.kafka.consumer.properties[metadata.max.age.ms]=500"})
class TopicsCreatedAfterStartupTests extends PaymentServiceTestBase {

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Test
    void theListenersFindTheRequestTopicsAndReadThemFromTheBeginning() throws Exception {
        assertThat(listeners.getListenerContainers()).hasSize(2)
                .allSatisfy(container -> assertThat(container.isRunning()).isTrue());

        broker.addTopics(new NewTopic(PaymentRequestKafkaTests.CHARGE_TOPIC, 3, (short) 1));
        kafka.send(PaymentRequestKafkaTests.CHARGE_TOPIC, "61",
                PaymentRequestKafkaTests.chargeRequest(61, 3, "25.50", APPROVED_CARD)).get();

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(transactions.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getOrderId()).isEqualTo(61)));
    }
}
