package com.basketry.merchant.auth;

/**
 * Values of the JWT {@code capabilities} claim issued by identity-service
 * (DESIGN.md Section 2). ADMIN never appears alongside BUYER or MERCHANT.
 * Copied from identity-service; there is no shared library.
 */
public enum Capability {
    BUYER,
    MERCHANT,
    ADMIN
}
