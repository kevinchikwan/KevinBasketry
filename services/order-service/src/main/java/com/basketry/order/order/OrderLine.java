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
 * One cart line and its reserve leg (DESIGN.md Section 2, order-service). Its
 * key, (orderId, productId), is inventory-service's idempotency key. The
 * merchant, name and price are snapshots from catalog-service at checkout.
 */
@Entity
@Table(name = "order_line")
@IdClass(OrderLine.Key.class)
public class OrderLine implements Persistable<OrderLine.Key> {

    @Id
    private long orderId;

    @Id
    private long productId;

    @Column(updatable = false)
    private long merchantId;

    @Column(updatable = false)
    private String productName;

    @Column(updatable = false)
    private BigDecimal unitPrice;

    @Column(updatable = false)
    private int quantity;

    @Column(updatable = false)
    private BigDecimal lineAmount;

    @Enumerated(EnumType.STRING)
    private LineStatus status;

    private String reasonCode;

    private int resends;

    private Instant compensationDueAt;

    /** The key is assigned, not generated, so Spring Data cannot tell on its own that the row is new. */
    @Transient
    private boolean isNew;

    protected OrderLine() {
    }

    OrderLine(long orderId, Amounts.LineAmounts line) {
        this.orderId = orderId;
        this.productId = line.productId();
        this.merchantId = line.merchantId();
        this.productName = line.productName();
        this.unitPrice = line.unitPrice();
        this.quantity = line.quantity();
        this.lineAmount = line.lineAmount();
        this.status = LineStatus.REQUESTED;
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
        return new Key(orderId, productId);
    }

    public long getOrderId() {
        return orderId;
    }

    public long getProductId() {
        return productId;
    }

    public long getMerchantId() {
        return merchantId;
    }

    public String getProductName() {
        return productName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getLineAmount() {
        return lineAmount;
    }

    public LineStatus getStatus() {
        return status;
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

    /** (orderId, productId). */
    public static class Key implements Serializable {

        private long orderId;
        private long productId;

        protected Key() {
        }

        public Key(long orderId, long productId) {
            this.orderId = orderId;
            this.productId = productId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.orderId == orderId && key.productId == productId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(orderId, productId);
        }
    }
}
