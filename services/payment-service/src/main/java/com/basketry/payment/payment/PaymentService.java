package com.basketry.payment.payment;

import com.basketry.payment.card.CardAuthorizer;
import com.basketry.payment.outbox.PaymentResultOutbox;
import com.basketry.payment.payment.ChargeOutcome.Charged;
import com.basketry.payment.payment.ChargeOutcome.Declined;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Charges and refunds (DESIGN.md Section 2 and Section 5, payment-service),
 * with no transport types: the Kafka listener only turns a record into a
 * command, calls this, and sends a {@link PaymentRequestRejectedException}
 * to its dead-letter topic.
 *
 * <p>Every call that returns writes exactly one outbox row with its result, in
 * the transaction that decided it. A repeated request is answered from the
 * stored row and publishes that stored result again, because the first
 * publish may have been lost. Nothing is ever charged twice.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentTransactionRepository transactions;
    private final PaymentResultOutbox outbox;
    private final CardAuthorizer authorizer;
    private final TransactionTemplate transaction;

    public PaymentService(PaymentTransactionRepository transactions, PaymentResultOutbox outbox,
                          CardAuthorizer authorizer, PlatformTransactionManager transactionManager) {
        this.transactions = transactions;
        this.outbox = outbox;
        this.authorizer = authorizer;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Authorizes and captures in one step, or answers a repeated request from
     * the stored row. Two concurrent first requests cannot both insert
     * (unique orderId and merchantId): the loser's transaction is rolled back,
     * and a new one answers from the winner's row. It has to be a new one,
     * because on PostgreSQL a failed statement aborts its whole transaction.
     */
    public ChargeOutcome authorize(ChargeCommand command) {
        Objects.requireNonNull(command, "command");
        ChargeOutcome repeated = transaction.execute(status -> answerFromStoredRow(command));
        if (repeated != null) {
            return repeated;
        }
        try {
            return transaction.execute(status -> chargeNew(command));
        } catch (DataIntegrityViolationException raceLost) {
            ChargeOutcome winner = transaction.execute(status -> answerFromStoredRow(command));
            if (winner == null) {
                throw raceLost; // not the (orderId, merchantId) constraint
            }
            return winner;
        }
    }

    /**
     * Refunds an APPROVED transaction, or answers a repeated refund. Unknown
     * reference, DECLINED transaction, or a reference of another order or
     * merchant: rejected, nothing changed and nothing published.
     */
    public Refunded refund(RefundCommand command) {
        Objects.requireNonNull(command, "command");
        return transaction.execute(status -> {
            PaymentTransaction stored = transactions.findByTransactionRef(command.transactionRef())
                    .orElseThrow(() -> new UnknownTransactionException(command.transactionRef()));
            if (stored.getOrderId() != command.orderId() || stored.getMerchantId() != command.merchantId()) {
                throw new RefundMismatchException(command, stored);
            }
            switch (stored.getStatus()) {
                case DECLINED -> throw new NotRefundableException(command.transactionRef(), stored.getStatus());
                // 0 rows: an overlapping refund got there first. Same answer either way.
                case APPROVED -> transactions.markRefunded(stored.getId(), Instant.now());
                case REFUNDED -> log.info("Repeated refund of {} (order {}, merchant {}): publishing refunded again",
                        command.transactionRef(), command.orderId(), command.merchantId());
            }
            outbox.recordRefunded(command.orderId(), command.merchantId());
            return new Refunded(command.orderId(), command.merchantId(), command.transactionRef());
        });
    }

    private ChargeOutcome chargeNew(ChargeCommand command) {
        String responseCode = authorizer.responseCode(command.cardNumber());
        boolean approved = CardAuthorizer.APPROVED.equals(responseCode);
        PaymentTransaction row = new PaymentTransaction(TransactionRefs.newRef(), command, responseCode,
                approved ? TransactionStatus.APPROVED : TransactionStatus.DECLINED, Instant.now());
        transactions.saveAndFlush(row); // a duplicate fails here, before the outbox row
        log.info("Charge for order {}, merchant {}: {} ({})", command.orderId(), command.merchantId(),
                row.getStatus(), responseCode);
        return publish(row);
    }

    /** Null if there is no stored row yet. */
    private ChargeOutcome answerFromStoredRow(ChargeCommand command) {
        PaymentTransaction stored = transactions.findByOrderIdAndMerchantId(command.orderId(), command.merchantId())
                .orElse(null);
        if (stored == null) {
            return null;
        }
        if (!stored.sameRequestAs(command)) {
            log.warn("Repeated charge for order {}, merchant {} differs from the first: amount {} (first {}),"
                            + " card ending {} (first {}). Keeping the first result; nothing charged.",
                    command.orderId(), command.merchantId(), command.amount(), stored.getAmount(),
                    command.cardLastFour(), stored.getCardLastFour());
        } else {
            log.info("Repeated charge for order {}, merchant {}: publishing the stored result again",
                    command.orderId(), command.merchantId());
        }
        return publish(stored);
    }

    /**
     * A charge request is answered as a charge: a transaction refunded since
     * is still {@code charged}, never {@code refunded} (DESIGN.md Section 5).
     */
    private ChargeOutcome publish(PaymentTransaction row) {
        return switch (row.getStatus()) {
            case APPROVED, REFUNDED -> {
                outbox.recordCharged(row.getOrderId(), row.getMerchantId(), row.getTransactionRef());
                yield new Charged(row.getTransactionRef());
            }
            case DECLINED -> {
                outbox.recordDeclined(row.getOrderId(), row.getMerchantId(), row.getResponseCode());
                yield new Declined(row.getResponseCode());
            }
        };
    }
}
