package com.basketry.inventory.request;

import static com.basketry.inventory.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.basketry.inventory.stock.ReservationStatus;
import com.basketry.inventory.support.InventoryServiceTestBase;
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
 * inventory-service may start before they exist. The listeners must start
 * anyway, find the topics by themselves and read them from the beginning.
 */
@EmbeddedKafka(
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = {
        "basketry.stock-requests.consumer.enabled=true",
        "spring.kafka.consumer.properties[metadata.max.age.ms]=500"})
class TopicsCreatedAfterStartupTests extends InventoryServiceTestBase {

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
        givenStock(42, 10);

        broker.addTopics(new NewTopic(StockRequestKafkaTests.RESERVE_TOPIC, 3, (short) 1));
        kafka.send(StockRequestKafkaTests.RESERVE_TOPIC, "61",
                StockRequestKafkaTests.request(61, 42, "3")).get();

        // An Optional, not reservation(): Awaitility retries only on an AssertionError, so a
        // NoSuchElementException would fail the first poll instead of waiting.
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(reservations.findByOrderIdAndProductId(61, 42))
                .hasValueSatisfying(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RESERVED)));
    }
}
