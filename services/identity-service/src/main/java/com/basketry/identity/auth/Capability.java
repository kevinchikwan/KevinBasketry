package com.basketry.identity.auth;

/**
 * Values of the JWT {@code capabilities} claim (DESIGN.md Section 2). ADMIN
 * never appears alongside BUYER or MERCHANT.
 */
public enum Capability {
    BUYER,
    MERCHANT,
    ADMIN
}
