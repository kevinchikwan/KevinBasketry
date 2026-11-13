package com.basketry.order.order;

/** The state of a charge leg (DESIGN.md Section 2, order-service). Checkout creates every leg REQUESTED. */
public enum ChargeStatus {
    REQUESTED,
    CHARGED,
    DECLINED,
    TIMED_OUT,
    REFUND_REQUESTED,
    REFUNDED,
    STUCK
}
