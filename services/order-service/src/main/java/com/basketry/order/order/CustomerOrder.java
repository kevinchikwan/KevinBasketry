package com.basketry.order.order;

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
import java.time.temporal.ChronoUnit;

/**
 * One checkout (DESIGN.md Section 2, order-service): table customer_order,
 * because ORDER is an SQL keyword. Its id is the orderId of every saga event.
 * Checkout writes it PENDING; the coordinator moves it on.
 */
@Entity
@Table(name = "customer_order")
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "customer_order_id")
    @SequenceGenerator(name = "customer_order_id", sequenceName = "customer_order_id_seq", allocationSize = 1)
    private Long id;

    @Column(updatable = false)
    private String orderReference;

    @Column(updatable = false)
    private long buyerAccountId;

    @Column(updatable = false)
    private String idempotencyKey;

    @Column(updatable = false)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private boolean needsAttention;

    @Column(updatable = false)
    private String couponCode;

    @Column(updatable = false)
    private BigDecimal totalAmount;

    @Column(updatable = false)
    private Instant deadlineAt;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant decidedAt;

    private Instant completedAt;

    protected CustomerOrder() {
    }

    /**
     * A new order, PENDING. Times are truncated to what the database stores, so
     * this object matches what a later read returns.
     */
    public CustomerOrder(String orderReference, long buyerAccountId, String idempotencyKey,
                         String requestFingerprint, String couponCode, BigDecimal totalAmount,
                         Instant createdAt, Instant deadlineAt) {
        this.orderReference = orderReference;
        this.buyerAccountId = buyerAccountId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.status = OrderStatus.PENDING;
        this.couponCode = couponCode;
        this.totalAmount = totalAmount;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        this.deadlineAt = deadlineAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** PENDING to FAILING: the failure is decided (DESIGN.md Section 2, order-service, the decision). */
    public void markFailing(Instant now) {
        requireStatus(OrderStatus.PENDING);
        this.status = OrderStatus.FAILING;
        this.decidedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    /** PENDING to SUCCEEDED: every leg positive. Final. */
    public void markSucceeded(Instant now) {
        requireStatus(OrderStatus.PENDING);
        this.status = OrderStatus.SUCCEEDED;
        this.decidedAt = now.truncatedTo(ChronoUnit.MICROS);
        this.completedAt = this.decidedAt;
    }

    /** FAILING to FAILED: every leg settled. */
    public void markFailed(Instant now) {
        requireStatus(OrderStatus.FAILING);
        this.status = OrderStatus.FAILED;
        this.completedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    /** FAILED back to FAILING: a late charge must be refunded (the reopen). decidedAt stays. */
    public void reopen() {
        requireStatus(OrderStatus.FAILED);
        this.status = OrderStatus.FAILING;
        this.completedAt = null;
    }

    public void setNeedsAttention(boolean needsAttention) {
        this.needsAttention = needsAttention;
    }

    private void requireStatus(OrderStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Order " + id + " is " + status + ", not " + expected);
        }
    }

    public Long getId() {
        return id;
    }

    public String getOrderReference() {
        return orderReference;
    }

    public long getBuyerAccountId() {
        return buyerAccountId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public boolean isNeedsAttention() {
        return needsAttention;
    }

    public String getCouponCode() {
        return couponCode;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
