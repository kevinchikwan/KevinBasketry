package com.basketry.catalog.merchantstatus;

import static com.basketry.catalog.merchantstatus.StatusEvents.event;
import static com.basketry.catalog.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.basketry.catalog.support.CatalogServiceTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

/**
 * Events published while the listener was not running, with no offset
 * committed for its group yet, are still read: a new catalog-service group
 * catches up on the topic's history. Mutation: auto-offset-reset: latest.
 */
@EmbeddedKafka(
        topics = StatusEvents.TOPIC,
        partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = {
        "basketry.merchant-status.consumer.enabled=true",
        "spring.kafka.listener.auto-startup=false"})
class EventsBeforeStartupTests extends CatalogServiceTestBase {

    private static final long MERCHANT_ID = 601;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Test
    void theyAreAppliedOnceTheListenerStarts() throws Exception {
        givenProduct(MERCHANT_ID, "Tea");
        String key = Long.toString(MERCHANT_ID);
        kafka.send(StatusEvents.TOPIC, key, event(MERCHANT_ID, false, 1)).get();
        kafka.send(StatusEvents.TOPIC, key, event(MERCHANT_ID, true, 2)).get();
        kafka.send(StatusEvents.TOPIC, key, event(MERCHANT_ID, false, 3)).get();

        listeners.getListenerContainers().forEach(MessageListenerContainer::start);

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(merchantStatusRepository.findById(MERCHANT_ID))
                .hasValueSatisfying(status -> {
                    assertThat(status.getVersion()).isEqualTo(3);
                    assertThat(status.isActive()).isFalse();
                }));
        assertThat(names(publicListingOf(MERCHANT_ID))).isEmpty();
    }
}
