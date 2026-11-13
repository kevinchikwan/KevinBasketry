package com.basketry.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.basketry.order.outbox.OrderOutbox;
import com.basketry.order.outbox.OutboxEvent;
import com.basketry.order.outbox.OutboxRelay;
import com.basketry.order.outbox.OutboxStatus;
import com.basketry.order.support.OrderServiceTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The card number appears in no log line and, once its outbox row is SENT or
 * CANCELLED, in no stored value (DESIGN.md Section 2, order-service, the card
 * number). Every path runs with a sentinel number while order-service logs at
 * DEBUG, Hibernate logs each statement, and Spring Kafka logs every send at
 * TRACE: that is what makes the record formatter matter.
 *
 * <p>Three loggers stay below TRACE, as DESIGN.md requires, because they print
 * whole records or parameters and no setting of ours can stop them:
 * {@code org.springframework.kafka.core.DefaultKafkaProducerFactory} and
 * {@code org.apache.kafka.clients} (DESIGN.md Section 5), and Hibernate's
 * parameter logging {@code org.hibernate.orm.jdbc.bind}: a charge request's
 * outbox row holds the number while it is PENDING (DESIGN.md Section 2,
 * order-service). A first run with all of Spring Kafka at TRACE showed the
 * producer factory printing the record.
 */
@ExtendWith(OutputCaptureExtension.class)
@EmbeddedKafka(
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = {
        "logging.level.com.basketry=DEBUG",
        "logging.level.org.hibernate.SQL=DEBUG",
        "logging.level.org.springframework.kafka=TRACE",
        "logging.level.org.springframework.kafka.core.DefaultKafkaProducerFactory=DEBUG",
        "logging.level.org.springframework.transaction=DEBUG",
        "logging.level.org.springframework.orm.jpa=DEBUG"})
class CardNumberLeakTests extends OrderServiceTestBase {

    /** Approved by payment-service's mock (not in its table) and used nowhere else. */
    private static final String SENTINEL = "4929117300568841";
    /** The digits a mask hides: catches a partial leak too, not only the whole number. */
    private static final String SENTINEL_HEAD = SENTINEL.substring(0, 12);

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OrderOutbox orderOutbox;

    /**
     * Mutations: the record formatter removed (KafkaTemplate's TRACE "Sending"
     * line prints the whole record); the relay's mark without the scrub; the
     * cancel without the scrub.
     */
    @Test
    void theCardNumberAppearsInNoLogLineAndNoStoredValue(CapturedOutput output) {
        givenBuyer();
        givenProduct(10, MERCHANT, "2.50");
        givenProduct(20, OTHER_MERCHANT, "1.25");
        givenMerchant(MERCHANT, "5.00");
        givenMerchant(OTHER_MERCHANT, "5.00");

        // A new order, its replay, the same key with another card, and a second order to cancel.
        checkout(command("key-0001", SENTINEL, "3.75", 10, 1, 20, 1));
        checkout(command("key-0001", SENTINEL, "3.75", 10, 1, 20, 1));
        catchThrowable(() -> checkout(command("key-0001", "4929117300561357", "3.75", 10, 1, 20, 1)));
        long toCancel = checkout(command("key-0002", SENTINEL, "2.50", 10, 1)).order().getId();
        // Refusals after the body: a changed price, a line that cannot be bought.
        catchThrowable(() -> checkout(command("key-0003", SENTINEL, "9.99", 10, 1)));
        catchThrowable(() -> checkout(command("key-0004", SENTINEL, "1.00", 99, 1)));
        // Bodies that break a rule.
        for (String malformed : new String[] {SENTINEL + "0000", SENTINEL + "X", " " + SENTINEL}) {
            catchThrowable(() -> checkout(command("key-0005", malformed, "2.50", 10, 1)));
        }
        // The cancel first, then the relay sends what is left.
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> orderOutbox.cancelUnsentRequests(toCancel));
        assertThat(relay.publishPending()).isTrue();

        // The checks would be empty if the logging were off.
        assertThat(output).contains("insert into customer_order").contains("Sending:");
        assertThat(output.getAll()).doesNotContain(SENTINEL_HEAD).doesNotContain("4929117300561357".substring(0, 12));
        List<OutboxEvent> rows = outboxRows();
        assertThat(rows).extracting(OutboxEvent::getStatus).containsOnly(OutboxStatus.SENT, OutboxStatus.CANCELLED);
        assertThat(rows).extracting(OutboxEvent::getStatus).contains(OutboxStatus.CANCELLED);
        assertThat(everyStoredValue()).isNotEmpty().noneMatch(value -> value.contains(SENTINEL_HEAD));
    }
}
