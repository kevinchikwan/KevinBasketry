package com.basketry.catalog.merchantstatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.catalog.support.CatalogServiceTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.TestPropertySource;

/**
 * The real listener with nothing on the broker's port. The context starting at
 * all is half the test: startup does not wait for Kafka. The service then
 * serves the public listing while the listener keeps trying in the background.
 * Mutation: missing-topics-fatal: true (the container checks the topic at
 * start, cannot reach the broker, and the context fails).
 */
@TestPropertySource(properties = {
        "basketry.merchant-status.consumer.enabled=true",
        "spring.kafka.bootstrap-servers=localhost:1"})
class KafkaDownTests extends CatalogServiceTestBase {

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Test
    void theServiceStartsAndServesTheListing() throws Exception {
        assertThat(listeners.getListenerContainers()).singleElement()
                .satisfies(container -> assertThat(container.isRunning()).isTrue());
        givenProduct(MERCHANT, "Tea");

        perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Tea"));
    }
}
