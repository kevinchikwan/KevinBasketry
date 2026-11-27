package com.basketry.promotion.usage;

/**
 * The insert broke a constraint other than the primary key: a bug, never a
 * repeat. It has no cause on purpose: the database failure it replaces would be
 * retried for ever (isDatabaseFailure searches the cause chain), and this goes
 * to the dead-letter topic instead. The violation itself is logged first.
 */
public class CouponUsageNotRecordedException extends RuntimeException {

    public CouponUsageNotRecordedException(String message) {
        super(message);
    }
}
