package com.basketry.identity.merchantlink;

import com.basketry.identity.account.Account;
import java.time.Instant;

/**
 * Body of a successful merchant-link call: the same account fields as
 * GET /accounts/{id} (never the password hash) plus what the call did.
 */
public record MerchantLinkResponse(
        long id,
        String email,
        boolean buyerActive,
        Long merchantId,
        boolean merchantActive,
        Instant createdAt,
        Outcome outcome) {

    public enum Outcome {
        /** A new account was created and linked (201). */
        CREATED,
        /** An existing account was linked (200). */
        LINKED,
        /** The account was already linked to this merchant; nothing changed (200). */
        ALREADY_LINKED
    }

    static MerchantLinkResponse of(Account account, Outcome outcome) {
        return new MerchantLinkResponse(
                account.getId(),
                account.getEmail(),
                account.isBuyerActive(),
                account.getMerchantId(),
                account.isMerchantActive(),
                account.getCreatedAt(),
                outcome);
    }
}
