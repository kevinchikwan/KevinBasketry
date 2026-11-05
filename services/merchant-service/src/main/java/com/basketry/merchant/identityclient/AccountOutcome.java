package com.basketry.merchant.identityclient;

/** What identity-service's merchant-link call did, from its {@code outcome} field (any 2xx). */
public enum AccountOutcome {
    /** A new account was created with the initial password and linked to the merchant. */
    CREATED,
    /** An existing account was linked to the merchant; its existing password was kept. */
    LINKED,
    /**
     * The account was already linked to this merchant, and nothing changed.
     * This can also be the answer when an earlier attempt timed out after
     * identity-service had already created the account, so the initial
     * password may have been set by that earlier attempt.
     */
    ALREADY_LINKED
}
