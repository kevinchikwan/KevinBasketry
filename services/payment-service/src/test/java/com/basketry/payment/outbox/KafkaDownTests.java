package com.basketry.payment.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.payment.support.PaymentServiceTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.TestPropertySource;

/**
 * Nothing listening on the broker's port. The context starting at all is half
 * the test: startup does not wait for Kafka. The listeners keep trying in the
 * background, and a result waits in the outbox.
 */
@TestPropertySource(properties = {
        "basketry.payment-requests.consumer.enabled=true",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.admin.operation-timeout=1s",
        "spring.kafka.admin.properties[request.timeout.ms]=500",
        "spring.kafka.admin.properties[default.api.timeout.ms]=500"})
class KafkaDownTests extends PaymentServiceTestBase {

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Test
    void theServiceStartsAndAResultWaitsInTheOutbox() {
        assertThat(listeners.getListenerContainers()).hasSize(2)
                .allSatisfy(container -> assertThat(container.isRunning()).isTrue());
        payments.authorize(charge(7, 3, "25.50", APPROVED_CARD));

        assertThat(relay.publishPending()).isFalse();

        assertThat(outboxRows()).singleElement().satisfies(event -> {
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(event.getAttempts()).isEqualTo(1);
            assertThat(event.getLastError()).contains("Kafka is not reachable");
        });
    }
}
