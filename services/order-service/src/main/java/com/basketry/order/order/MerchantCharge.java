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
