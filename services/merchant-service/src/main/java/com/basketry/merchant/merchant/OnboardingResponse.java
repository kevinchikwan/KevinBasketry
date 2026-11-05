package com.basketry.merchant.merchant;

import com.basketry.merchant.identityclient.AccountOutcome;
import com.basketry.merchant.identityclient.LinkErrorCode;
import com.basketry.merchant.identityclient.LinkResult;
import com.basketry.merchant.identityclient.LinkResult.Failed;
import com.basketry.merchant.identityclient.LinkResult.Linked;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Body of POST /merchants (201) and POST /merchants/{code}/account-link
 * (200): the merchant's fields plus what the link attempt did. Exactly one of
 * {@code accountOutcome} (the attempt succeeded, {@code accountLinkStatus} is
 * LINKED) and {@code accountLinkError} (it failed, the merchant stays
 * PENDING) is set; the other is null. Never includes a password.
 *
 * <p>{@code accountOutcome} ALREADY_LINKED: the account was already linked to
 * this merchant, and nothing changed. This can also be the answer when an
 * earlier attempt timed out after identity-service had already created the
 * account, so the initial password may have been set by that earlier attempt.
 */
public record OnboardingResponse(
        long id,
        String merchantCode,
        String name,
        String category,
        boolean kycVerified,
        boolean active,
        BigDecimal commissionRate,
        String loginEmail,
        AccountLinkStatus accountLinkStatus,
        Instant createdAt,
        AccountOutcome accountOutcome,
        AccountLinkError accountLinkError) {

    /** @param violations failed password rules for WEAK_PASSWORD, otherwise null */
    public record AccountLinkError(LinkErrorCode code, String detail, List<String> violations) {
    }

    static OnboardingResponse of(Merchant merchant, LinkResult result) {
        AccountOutcome outcome = result instanceof Linked linked ? linked.outcome() : null;
        AccountLinkError error = result instanceof Failed failed
                ? new AccountLinkError(failed.code(), failed.detail(), failed.violations())
                : null;
        return new OnboardingResponse(
                merchant.getId(),
                merchant.getMerchantCode(),
                merchant.getName(),
                merchant.getCategory(),
                merchant.isKycVerified(),
                merchant.isActive(),
                merchant.getCommissionRate(),
                merchant.getLoginEmail(),
                merchant.getAccountLinkStatus(),
                merchant.getCreatedAt(),
                outcome,
                error);
    }
}
