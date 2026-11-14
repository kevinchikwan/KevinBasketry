package com.basketry.order.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.domain.Persistable;

/**
 * A charge leg: one per merchant in the order whose amount is above zero
 * (DESIGN.md Section 2, order-service). Its key, (orderId, merchantId), is
 * payment-service's idempotency key. The amounts and the commission rate are
 * fixed at checkout (see {@link Amounts}).
 */
@Entity
@Table(name = "merchant_charge")
@IdClass(MerchantCharge.Key.class)
public class MerchantCharge implements Persistable<MerchantCharge.Key> {

    @Id
    private long orderId;

    @Id
    private long merchantId;

    @Column(updatable = false)
    private BigDecimal subtotal;

    @Column(updatable = false)
    private BigDecimal discount;

    @Column(updatable = false)
    private BigDecimal amount;

    @Column(updatable = false)
    private BigDecimal commissionRate;

    @Column(updatable = false)
    private BigDecimal commission;

    @Enumerated(EnumType.STRING)
    private ChargeStatus status;

    private String transactionRef;

    private String reasonCode;

    private int resends;

    private Instant compensationDueAt;

    /** The key is assigned, not generated, so Spring Data cannot tell on its own that the row is new. */
    @Transient
    private boolean isNew;

    protected MerchantCharge() {
    }

    MerchantCharge(long orderId, Amounts.MerchantAmounts amounts) {
        this.orderId = orderId;
        this.merchantId = amounts.merchantId();
        this.subtotal = amounts.subtotal();
        this.discount = amounts.discount();
        this.amount = amounts.amount();
        this.commissionRate = amounts.commissionRate();
        this.commission = amounts.commission();
        this.status = ChargeStatus.REQUESTED;
        this.isNew = true;
    }

    /** A charged result for a leg still REQUESTED. */
    public void charged(String transactionRef) {
        this.status = ChargeStatus.CHARGED;
        this.transactionRef = transactionRef;
    }

    /** A declined result for a leg still REQUESTED; {@code reasonCode} is the response code. */
    public void declined(String reasonCode) {
        this.status = ChargeStatus.DECLINED;
        this.reasonCode = reasonCode;
    }

    /** No result by the deadline: nothing to refund, since there is no transactionRef. */
    public void timedOut() {
        this.status = ChargeStatus.TIMED_OUT;
        this.reasonCode = NO_RESULT;
    }

    /**
     * A refund requested, the first time: from CHARGED, or from TIMED_OUT when
     * the charge arrives late (with its transactionRef). The reason code of a
     * late charge stays NO_RESULT: the leg failed the order.
     */
    public void refundRequested(String transactionRef, Instant dueAt) {
        this.status = ChargeStatus.REFUND_REQUESTED;
        this.transactionRef = transactionRef;
        this.resends = 0;
        this.compensationDueAt = dueAt;
    }

    /** The refund sent again; due again at {@code dueAt}. */
    public void refundResent(Instant dueAt) {
        this.resends++;
        this.compensationDueAt = dueAt;
    }

    /** Resent the maximum number of times and still not confirmed. */
    public void stuck() {
        this.status = ChargeStatus.STUCK;
        this.compensationDueAt = null;
    }

    public void refunded() {
        this.status = ChargeStatus.REFUNDED;
        this.compensationDueAt = null;
    }

    /** DECLINED, REFUNDED or TIMED_OUT: it can never need a compensation again. */
    public boolean settled() {
        return status == ChargeStatus.DECLINED || status == ChargeStatus.REFUNDED || status == ChargeStatus.TIMED_OUT;
    }

    /** Fails the order: DECLINED or TIMED_OUT. */
    public boolean negative() {
        return status == ChargeStatus.DECLINED || status == ChargeStatus.TIMED_OUT;
    }

    public static final String NO_RESULT = "NO_RESULT";

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public Key getId() {
        return new Key(orderId, merchantId);
    }

    public long getOrderId() {
        return orderId;
    }

    public long getMerchantId() {
        return merchantId;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDiscount() {
        return discount;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }

    public BigDecimal getCommission() {
        return commission;
    }

    public ChargeStatus getStatus() {
        return status;
    }

    public String getTransactionRef() {
        return transactionRef;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public int getResends() {
        return resends;
    }

    public Instant getCompensationDueAt() {
        return compensationDueAt;
    }

    /** (orderId, merchantId). */
    public static class Key implements Serializable {

        private long orderId;
        private long merchantId;

        protected Key() {
        }

        public Key(long orderId, long merchantId) {
            this.orderId = orderId;
            this.merchantId = merchantId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.orderId == orderId && key.merchantId == merchantId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(orderId, merchantId);
        }
    }
}
