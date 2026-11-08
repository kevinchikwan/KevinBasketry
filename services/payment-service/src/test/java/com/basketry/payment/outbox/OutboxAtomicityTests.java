package com.basketry.payment.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basketry.payment.card.CardAuthorizer;
import com.basketry.payment.payment.PaymentService;
import com.basketry.payment.payment.RefundCommand;
import com.basketry.payment.payment.ChargeOutcome.Charged;
import com.basketry.payment.payment.TransactionStatus;
import com.basketry.payment.support.PaymentServiceTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The result and its outbox row commit together or not at all (DESIGN.md
 * Section 5): a result that is stored but never published would leave the
 * saga waiting; a published result that is not stored would be a lie.
 */
class OutboxAtomicityTests extends PaymentServiceTestBase {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CardAuthorizer authorizer;

    /** The Spring bean, so its @Transactional(MANDATORY) applies. */
    @Autowired
    private PaymentResultOutbox writer;

    @Test
    void theOutboxWriterRefusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> writer.recordCharged(7, 3, "TXN-0123456789abcdef0123456789abcdef"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outboxRows()).isEmpty();
    }

    @Test
    void whenTheOutboxWriteFailsTheChargeIsNotStored() {
        PaymentService failingOutbox = new PaymentService(transactions, new FailingOutbox(outbox, json),
                authorizer, transactionManager);

        assertThatThrownBy(() -> failingOutbox.authorize(charge(7, 3, "25.50", APPROVED_CARD)))
                .hasMessage("outbox write failed");
        assertThat(transactions.findAll()).isEmpty();
        assertThat(outboxRows()).isEmpty();
    }

    @Test
    void whenTheOutboxWriteFailsTheRefundIsNotStored() {
        String ref = ((Charged) payments.authorize(charge(7, 3, "25.50", APPROVED_CARD))).transactionRef();
        PaymentService failingOutbox = new PaymentService(transactions, new FailingOutbox(outbox, json),
                authorizer, transactionManager);

        assertThatThrownBy(() -> failingOutbox.refund(new RefundCommand(7, 3, ref)))
                .hasMessage("outbox write failed");
        assertThat(onlyTransaction().getStatus()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(onlyTransaction().getRefundedAt()).isNull();
        assertThat(outboxRows()).hasSize(1);
    }

    /** A rollback of the surrounding transaction takes both rows with it: neither commits on its own. */
    @Test
    void aRollbackRemovesBothTheTransactionRowAndItsOutboxRow() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            payments.authorize(charge(7, 3, "25.50", APPROVED_CARD));
            status.setRollbackOnly();
        });

        assertThat(transactions.findAll()).isEmpty();
        assertThat(outboxRows()).isEmpty();
    }

    /** Writes its row, then fails, inside the caller's transaction. */
    private static final class FailingOutbox extends PaymentResultOutbox {

        FailingOutbox(OutboxRepository outbox, tools.jackson.databind.json.JsonMapper json) {
            super(outbox, json);
        }

        @Override
        public void recordCharged(long orderId, long merchantId, String transactionRef) {
            super.recordCharged(orderId, merchantId, transactionRef);
            throw new IllegalStateException("outbox write failed");
        }

        @Override
        public void recordRefunded(long orderId, long merchantId) {
            super.recordRefunded(orderId, merchantId);
            throw new IllegalStateException("outbox write failed");
        }
    }
}
