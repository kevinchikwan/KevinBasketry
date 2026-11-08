package com.basketry.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.payment.card.CardAuthorizer;
import com.basketry.payment.outbox.PaymentResultOutbox;
import com.basketry.payment.payment.ChargeOutcome.Charged;
import com.basketry.payment.support.PaymentServiceTestBase;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The full card number never reaches a log line, the database, an outbox
 * payload or an exception message (DESIGN.md Section 2, payment-service).
 * Every path runs with a sentinel number while Hibernate logs each SQL
 * statement and every bound parameter: if the number were ever written to the
 * database, it would show up here.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {
        "logging.level.com.basketry=DEBUG",
        "logging.level.org.hibernate.SQL=DEBUG",
        "logging.level.org.hibernate.orm.jdbc.bind=TRACE"})
class CardNumberLeakTests extends PaymentServiceTestBase {

    /** Approved by the mock (not in its table) and used nowhere else. */
    private static final String SENTINEL = "4929117300568841";
    private static final String OTHER_SENTINEL = "4929117300561357";
    /** The digits a mask hides: catches a partial leak too, not only the whole number. */
    private static final String SENTINEL_HEAD = SENTINEL.substring(0, 12);

    @Autowired
    private PaymentResultOutbox resultOutbox;

    @Autowired
    private CardAuthorizer authorizer;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void theCardNumberAppearsInNoLogLineStoredValueOrMessage(CapturedOutput output) {
        // A new charge, a repeat, a repeat with another card and amount (the WARN), and a lost race.
        Charged charged = (Charged) payments.authorize(charge(7, 3, "25.50", SENTINEL));
        payments.authorize(charge(7, 3, "25.50", SENTINEL));
        payments.authorize(charge(7, 3, "30.00", OTHER_SENTINEL));
        new PaymentService(firstLookupMisses(), resultOutbox, authorizer, transactionManager)
                .authorize(charge(7, 3, "25.50", SENTINEL));
        // A decline, a refund and its repeat.
        payments.authorize(charge(8, 3, "10.00", "4000000000000010"));
        payments.refund(new RefundCommand(7, 3, charged.transactionRef()));
        payments.refund(new RefundCommand(7, 3, charged.transactionRef()));
        // Invalid requests: their messages must not echo the number either.
        for (String malformed : new String[] {SENTINEL + "0000", SENTINEL + "X", " " + SENTINEL}) {
            assertThatThrownBy(() -> new ChargeCommand(9, 3, new BigDecimal("1.00"), malformed))
                    .isInstanceOf(InvalidPaymentRequestException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SENTINEL_HEAD));
        }
        assertThat(charge(9, 3, "1.00", SENTINEL).toString()).doesNotContain(SENTINEL_HEAD);

        // The check would be empty if Hibernate's statement and parameter logging were off.
        assertThat(output).contains("insert into payment_transaction").contains("binding parameter");
        assertThat(output.getAll()).doesNotContain(SENTINEL_HEAD)
                .doesNotContain(OTHER_SENTINEL.substring(0, 12));
        assertThat(everyStoredValue()).isNotEmpty().noneMatch(value -> value.contains(SENTINEL_HEAD));
        assertThat(everyStoredValue()).contains("8841");   // the last 4 digits are kept
    }
}
