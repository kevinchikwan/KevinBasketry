package com.basketry.inventory.stock;

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
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * The decided outcome of one reserve request for one order line, kept for good
 * (DESIGN.md Section 2, inventory-service): the idempotency record. Only
 * RESERVED becomes RELEASED, through
 * {@link StockReservationRepository#markReleased}; nothing else ever changes.
 */
@Entity
@Table(name = "stock_reservation")
@IdClass(ReservationKey.class)
public class StockReservation implements Persistable<ReservationKey> {

    @Id
    private long orderId;

    @Id
    private long productId;

    @Column(updatable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private ReservationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private ReasonCode reasonCode;

    @Column(updatable = false)
    private Instant createdAt;

    @Column(updatable = false)
    private Instant releasedAt;

    /** The key is assigned, so save must insert, never merge: a duplicate has to hit the primary key. */
    @Transient
    private boolean isNew = true;

    protected StockReservation() {
    }

    private StockReservation(long orderId, long productId, int quantity, ReservationStatus status,
                             ReasonCode reasonCode, Instant createdAt) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.status = status;
        this.reasonCode = reasonCode;
        this.createdAt = createdAt;
    }

    static StockReservation reserved(long orderId, long productId, int quantity, Instant now) {
        return new StockReservation(orderId, productId, quantity, ReservationStatus.RESERVED, null, now);
    }

    static StockReservation failed(long orderId, long productId, int quantity, ReasonCode reason, Instant now) {
        return new StockReservation(orderId, productId, quantity, ReservationStatus.FAILED, reason, now);
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    @Override
    public ReservationKey getId() {
        return new ReservationKey(orderId, productId);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public long getOrderId() {
        return orderId;
    }

    public long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public ReasonCode getReasonCode() {
        return reasonCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }
}
