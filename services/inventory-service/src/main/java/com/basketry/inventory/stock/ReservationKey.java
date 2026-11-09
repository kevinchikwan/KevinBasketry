package com.basketry.inventory.stock;

import java.io.Serializable;
import java.util.Objects;

/** The primary key of a stock_reservation row, and the idempotency key of a request. */
public class ReservationKey implements Serializable {

    private long orderId;
    private long productId;

    protected ReservationKey() {
    }

    public ReservationKey(long orderId, long productId) {
        this.orderId = orderId;
        this.productId = productId;
    }

    public long getOrderId() {
        return orderId;
    }

    public long getProductId() {
        return productId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReservationKey key && key.orderId == orderId && key.productId == productId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId, productId);
    }
}
