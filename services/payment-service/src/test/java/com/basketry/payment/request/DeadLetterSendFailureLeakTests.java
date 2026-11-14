package com.basketry.payment.request;

import static com.basketry.payment.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.basketry.payment.support.PaymentServiceTestBase;
import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

/**
 * A dead-letter publish that fails: Spring Boot's default producer listener,
 * LoggingProducerListener, logs up to 100 characters of the record's value at
 * ERROR. A charge request's dead letter is the original value, card number
 * included, so a rejected request whose cardNumber comes first would put the
 * number in that line. The publish is made to fail with a tiny
 * max.request.size on the service's producer; the request itself is sent by
 * the test's own producer, which has no such limit.
 */
@ExtendWith(OutputCaptureExtension.class)
@EmbeddedKafka(
        topics = {PaymentRequestKafkaTests.CHARGE_TOPIC, PaymentRequestKafkaTests.REFUND_TOPIC},
        partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = {
        "basketry.payment-requests.consumer.enabled=true",
        "spring.kafka.producer.properties.max.request.size=150"})
class DeadLetterSendFailureLeakTests extends PaymentServiceTestBase {

    private static final String SENTINEL = "4929117300568841";

    @Autowired
    private EmbeddedKafkaBroker broker;

    /**
     * payment-service's own producer listener logs the topic and the key only.
     * Mutation: the ProducerListener bean removed (Boot's LoggingProducerListener
     * then logs the value, card number first).
     */
    @Test
    void aFailedDeadLetterPublishLogsNoCardNumber(CapturedOutput output) throws Exception {
        // Rejected (orderId is a string), with the card number as the first field.
        String rejected = "{\"cardNumber\":\"" + SENTINEL + "\",\"schemaVersion\":1,\"orderId\":\"x\","
                + "\"merchantId\":3,\"amount\":25.50}";
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()),
                new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(PaymentRequestKafkaTests.CHARGE_TOPIC, "801", rejected)).get();
        }

        // The publish was tried and failed.
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(output).contains("RecordTooLargeException"));

        assertThat(output.getAll()).doesNotContain(SENTINEL.substring(0, 12));
        assertThat(output).contains("Could not send a record to order.merchant-charge.requested.DLT (key 801)");
    }
}
