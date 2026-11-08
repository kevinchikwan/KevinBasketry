package com.basketry.payment.request;

import static com.basketry.payment.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.basketry.payment.outbox.OutboxStatus;
import com.basketry.payment.outbox.PaymentResultEvents;
import com.basketry.payment.payment.PaymentService;
import com.basketry.payment.support.PaymentServiceTestBase;
import com.basketry.payment.support.TopicReader;
import java.util.List;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The Kafka side of the card-number rule (DESIGN.md Sections 2 and 5): a
 * sentinel number travels every listener path, accepted, rejected for each
 * kind of reason, failed by a bug, and retried after a database failure. It
 * must stay out of every dead-letter header and key, every log line (Spring
 * Kafka at TRACE, the Kafka client at DEBUG, Hibernate's bound parameters)
 * and every stored value. Two loggers print whole records at TRACE and stay
 * below it here, as they must in production: the Kafka client and Spring's
 * DefaultKafkaProducerFactory. Only the dead letters' values keep it, on purpose.
 */
@ExtendWith(OutputCaptureExtension.class)
@EmbeddedKafka(
        topics = {PaymentRequestKafkaTests.CHARGE_TOPIC, PaymentRequestKafkaTests.REFUND_TOPIC},
        partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = {
        "basketry.payment-requests.consumer.enabled=true",
        "basketry.payment-requests.retry-initial=5ms",
        "basketry.payment-requests.retry-max=20ms",
        "basketry.outbox.relay.enabled=true",
        "basketry.outbox.relay.interval=50ms",
        "logging.level.com.basketry=DEBUG",
        "logging.level.org.springframework.kafka=TRACE",
        // Prints whole records at TRACE, whatever the formatter: never at TRACE (DESIGN.md Section 5).
        "logging.level.org.springframework.kafka.core.DefaultKafkaProducerFactory=DEBUG",
        "logging.level.org.apache.kafka=DEBUG",
        "logging.level.org.hibernate.SQL=DEBUG",
        "logging.level.org.hibernate.orm.jdbc.bind=TRACE"})
class KafkaCardNumberLeakTests extends PaymentServiceTestBase {

    /** Approved by the mock (not in its table); the same sentinels as CardNumberLeakTests. */
    private static final String SENTINEL = "4929117300568841";
    private static final String OTHER_SENTINEL = "4929117300561357";
    private static final String SENTINEL_HEAD = SENTINEL.substring(0, 12);
    private static final String OTHER_HEAD = OTHER_SENTINEL.substring(0, 12);
    private static final String CHARGE = PaymentRequestKafkaTests.CHARGE_TOPIC;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @MockitoSpyBean
    private PaymentService spiedPayments;

    private void send(long orderId, String value) throws Exception {
        kafka.send(CHARGE, Long.toString(orderId), value).get();
    }

    private static String raw(long orderId, String cardNumberJson) {
        return """
                {"schemaVersion":1,"orderId":%d,"merchantId":3,"amount":25.50,"cardNumber":%s}"""
                .formatted(orderId, cardNumberJson);
    }

    @Test
    void theCardNumberStaysOutOfHeadersLogsAndStoredValues(CapturedOutput output) throws Exception {
        // Accepted: a new charge, its repeat, and a repeat with another card and amount (the WARN).
        send(701, PaymentRequestKafkaTests.chargeRequest(701, 3, "25.50", SENTINEL));
        send(701, PaymentRequestKafkaTests.chargeRequest(701, 3, "25.50", SENTINEL));
        send(701, PaymentRequestKafkaTests.chargeRequest(701, 3, "30.00", OTHER_SENTINEL));
        // A database failure, retried, then accepted.
        doThrow(new DataAccessResourceFailureException("database down"))
                .doThrow(new DataAccessResourceFailureException("database down"))
                .doCallRealMethod()
                .when(spiedPayments).authorize(argThat(command -> command.orderId() == 702));
        send(702, PaymentRequestKafkaTests.chargeRequest(702, 3, "25.50", SENTINEL));
        // A bug.
        doThrow(new IllegalStateException("a bug"))
                .when(spiedPayments).authorize(argThat(command -> command.orderId() == 703));
        send(703, PaymentRequestKafkaTests.chargeRequest(703, 3, "25.50", SENTINEL));
        // Rejected, each for another reason, the number placed where a message could pick it up.
        List<String> rejected = List.of(
                raw(704, "\"" + SENTINEL + "X\""),                     // not 12 to 19 digits
                raw(705, SENTINEL),                                      // a number, not a string
                raw(706, SENTINEL + "x"),                                // not JSON
                raw(707, "\"" + SENTINEL + "\"").replace("}", ",\"" + SENTINEL + "\":1}"),   // unknown field
                raw(708, "\"1\"").replace("\"schemaVersion\":1", "\"schemaVersion\":" + SENTINEL),
                raw(709, "\"" + SENTINEL + "\",\"cardNumber\":\"" + SENTINEL + "\""));  // repeated field
        long order = 704;
        for (String value : rejected) {
            send(order++, value);
        }

        Set<String> deadKeys = Set.of("703", "704", "705", "706", "707", "708", "709");
        List<ConsumerRecord<String, String>> deadLetters = TopicReader.read(broker, CHARGE + ".DLT",
                record -> deadKeys.contains(record.key()), deadKeys.size());
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(outboxRows()).hasSize(4)
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT)));
        TopicReader.readKey(broker, PaymentResultEvents.CHARGED_TOPIC, "702", 1);

        assertThat(deadLetters).hasSize(deadKeys.size()).allSatisfy(deadLetter -> {
            assertThat(TopicReader.headers(deadLetter)).noneMatch(header -> header.contains(SENTINEL_HEAD));
            assertThat(deadLetter.key()).doesNotContain(SENTINEL_HEAD);
        });
        // Kept on purpose: a replay must be the same request.
        assertThat(deadLetters).anySatisfy(deadLetter -> assertThat(deadLetter.value()).contains(SENTINEL));

        // The check below would be empty if these logs were off. Whatever the record formatter
        // prints, KafkaTemplate's TRACE line starts with "Sending: ", so this holds either way.
        assertThat(output).contains("o.s.kafka.core.KafkaTemplate")
                .contains("Sending: ")
                .contains("Sending record order.merchant-charge.requested-")
                .contains("binding parameter")
                .contains("differs from the first");
        // The rule itself, checked before anything about the log format, so a leak is reported as one.
        assertThat(output.getAll()).doesNotContain(SENTINEL_HEAD).doesNotContain(OTHER_HEAD);
        assertThat(everyStoredValue()).isNotEmpty()
                .noneMatch(value -> value.contains(SENTINEL_HEAD) || value.contains(OTHER_HEAD));
        // Spring Kafka logs a dead letter's place only.
        assertThat(output).contains("Sending: order.merchant-charge.requested.DLT-");
    }
}
