package com.basketry.identity.account;

import java.time.Instant;

/**
 * Body of GET /accounts/{id}. Never includes the password hash.
 * {@code buyerActive} is what order-service reads for its live check at
 * checkout (DESIGN.md Section 2, Tokens).
 */
public record AccountResponse(
        long id,
        String email,
        boolean buyerActive,
        Long merchantId,
        boolean merchantActive,
        Instant createdAt) {

    static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getEmail(),
                account.isBuyerActive(),
                account.getMerchantId(),
                account.isMerchantActive(),
                account.getCreatedAt());
    }
}
