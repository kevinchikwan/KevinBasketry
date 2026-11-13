package com.basketry.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.order.support.OrderServiceTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

/**
 * A send the broker refuses: Spring Boot's default producer listener would log
 * the start of the record's value at ERROR. order-service's own listener logs
 * only the topic and the key. The send is made to fail by a tiny
 * max.request.size, so the topics exist and the record really reaches the
 * producer.
 */
@ExtendWith(OutputCaptureExtension.class)
@EmbeddedKafka(
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = "spring.kafka.producer.properties.max.request.size=150")
class KafkaSendFailureLogTests extends OrderServiceTestBase {

    private static final String SENTINEL = "4929117300568841";

    @Autowired
    private OutboxRelay relay;

    /**
     * Our listener's line, never the value; the row stays PENDING with its card
     * number for the retry, and its lastError holds no part of it. Mutation: the
     * ProducerListener bean removed (Boot's LoggingProducerListener is used).
     */
    @Test
    void aFailedSendLogsTheTopicAndKeyOnly(CapturedOutput output) {
        givenBuyer();
        givenProduct(10, MERCHANT, "2.50");
        givenMerchant(MERCHANT, "5.00");
        long orderId = checkout(command("key-0001", SENTINEL, "2.50", 10, 1)).order().getId();

        assertThat(relay.publishPending()).isFalse();

        assertThat(output).contains("Could not send a record to order.merchant-charge.requested (key " + orderId + ")");
        assertThat(output.getAll()).doesNotContain(SENTINEL.substring(0, 4) + SENTINEL.substring(4, 12));
        OutboxEvent charge = outboxRows().getFirst();
        assertThat(charge.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(charge.getAttempts()).isEqualTo(1);
        assertThat(charge.getLastError()).doesNotContain(SENTINEL.substring(0, 12));
    }
}
