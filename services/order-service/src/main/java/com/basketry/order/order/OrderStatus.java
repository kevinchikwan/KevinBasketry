package com.basketry.order.order;

/** DESIGN.md Section 2, order-service, order status. */
public enum OrderStatus {
    /** Results outstanding, none negative. */
    PENDING,
    /** Every charge leg CHARGED and every line RESERVED. Final. */
    SUCCEEDED,
    /** Failure decided; compensations or results still outstanding. */
    FAILING,
    /** Failure decided and every leg settled. Final, unless a late charge reopens it. */
    FAILED
}
