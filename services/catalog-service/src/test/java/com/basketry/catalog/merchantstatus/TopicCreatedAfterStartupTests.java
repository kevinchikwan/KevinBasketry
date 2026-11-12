package com.basketry.catalog.merchantstatus;

import static com.basketry.catalog.merchantstatus.StatusEvents.event;
import static com.basketry.catalog.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.basketry.catalog.support.CatalogServiceTestBase;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

/**
 * The normal first run, as in identity-service: merchant-service creates the
 * topic on the first status change, so catalog-service usually starts before
 * it exists. The listener must start anyway, find the topic by itself and read
 * it from its beginning. Mutation: missing-topics-fatal: true (the context
 * would not start).
 */
@EmbeddedKafka(
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = {
        "basketry.merchant-status.consumer.enabled=true",
        "spring.kafka.consumer.properties[metadata.max.age.ms]=500"})
class TopicCreatedAfterStartupTests extends CatalogServiceTestBase {

    private static final long MERCHANT_ID = 501;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Test
    void theListenerFindsTheTopicAndReadsItFromTheBeginning() throws Exception {
        givenProduct(MERCHANT_ID, "Tea");
        MessageListenerContainer container = listeners.getListenerContainers().iterator().next();
        assertThat(container.isRunning()).isTrue();

        broker.addTopics(new NewTopic(StatusEvents.TOPIC, 3, (short) 1));
        kafka.send(StatusEvents.TOPIC, Long.toString(MERCHANT_ID), event(MERCHANT_ID, false, 1)).get();

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(merchantStatusRepository.findById(MERCHANT_ID))
                .hasValueSatisfying(status -> assertThat(status.isActive()).isFalse()));
        assertThat(names(publicListingOf(MERCHANT_ID))).isEmpty();
    }
}
