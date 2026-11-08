package com.basketry.payment.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One charge attempt for one merchant of one order, whatever its outcome
 * (DESIGN.md Section 2, payment-service). It is also the idempotency record:
 * (orderId, merchantId) is unique. Only {@code status} and {@code refundedAt}
 * ever change, through {@link PaymentTransactionRepository#markRefunded}.
 * The full card number is not a field, only its last 4 digits.
 */
@Entity
@Table(name = "payment_transaction")
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "payment_transaction_id")
    @SequenceGenerator(name = "payment_transaction_id", sequenceName = "payment_transaction_id_seq",
            allocationSize = 1)
    private Long id;

    @Column(updatable = false)
    private String transactionRef;

    @Column(updatable = false)
    private long orderId;

    @Column(updatable = false)
    private long merchantId;

    @Column(updatable = false)
    private BigDecimal amount;

    @Column(name = "card_last4", updatable = false)
    private String cardLastFour;

    @Column(updatable = false)
    private String responseCode;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private TransactionStatus status;

    @Column(updatable = false)
    private Instant createdAt;

    @Column(updatable = false)
    private Instant refundedAt;

    protected PaymentTransaction() {
    }

    PaymentTransaction(String transactionRef, ChargeCommand command, String responseCode,
                       TransactionStatus status, Instant createdAt) {
        this.transactionRef = transactionRef;
        this.orderId = command.orderId();
        this.merchantId = command.merchantId();
        this.amount = command.amount();
        this.cardLastFour = command.cardLastFour();
        this.responseCode = responseCode;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** Same amount and same last 4 digits. A different card with the same last 4 cannot be told apart. */
    boolean sameRequestAs(ChargeCommand command) {
        return amount.compareTo(command.amount()) == 0 && cardLastFour.equals(command.cardLastFour());
    }

    public Long getId() {
        return id;
    }

    public String getTransactionRef() {
        return transactionRef;
    }

    public long getOrderId() {
        return orderId;
    }

    public long getMerchantId() {
        return merchantId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCardLastFour() {
        return cardLastFour;
    }

    public String getResponseCode() {
        return responseCode;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }
}
