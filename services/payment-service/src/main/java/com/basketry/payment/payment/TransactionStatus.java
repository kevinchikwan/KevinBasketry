package com.basketry.payment.payment;

/** APPROVED can become REFUNDED; nothing else ever changes. */
public enum TransactionStatus {
    APPROVED,
    DECLINED,
    REFUNDED
}
