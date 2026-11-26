package com.basketry.merchant.merchant;

import java.math.BigDecimal;
import java.time.Instant;

/** Body of GET /merchants/{code} and PATCH /merchants/{code}, and each item of GET /merchants. */
public record MerchantResponse(
        long id,
        String merchantCode,
        String name,
        String category,
        boolean kycVerified,
        boolean active,
        BigDecimal commissionRate,
        String loginEmail,
        AccountLinkStatus accountLinkStatus,
        Instant createdAt) {

    static MerchantResponse from(Merchant merchant) {
        return new MerchantResponse(
                merchant.getId(),
                merchant.getMerchantCode(),
                merchant.getName(),
                merchant.getCategory(),
                merchant.isKycVerified(),
                merchant.isActive(),
                merchant.getCommissionRate(),
                merchant.getLoginEmail(),
                merchant.getAccountLinkStatus(),
                merchant.getCreatedAt());
    }

    /**
     * Without the name and the login email: Spring logs the answer it writes at
     * TRACE ("Writing [...]"), also inside a MerchantPage.
     */
    @Override
    public String toString() {
        return "MerchantResponse[id=" + id + ", merchantCode=" + merchantCode + ", name=***, category=" + category
                + ", kycVerified=" + kycVerified + ", active=" + active + ", commissionRate=" + commissionRate
                + ", loginEmail=***, accountLinkStatus=" + accountLinkStatus + ", createdAt=" + createdAt + "]";
    }
}
