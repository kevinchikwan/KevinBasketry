package com.basketry.merchant.merchant;

/**
 * Whether the merchant's login account has been linked in identity-service
 * (DESIGN.md Section 2, merchant-service).
 */
public enum AccountLinkStatus {
    /** Saved, but no merchant-link call has succeeded yet. Admin retries via POST /merchants/{code}/account-link. */
    PENDING,
    /** A merchant-link call answered 2xx. Final: it never goes back to PENDING. */
    LINKED
}
