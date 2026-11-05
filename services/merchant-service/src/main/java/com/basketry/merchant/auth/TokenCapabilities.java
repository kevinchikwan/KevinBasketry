package com.basketry.merchant.auth;

import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The one place that reads the JWT's {@code capabilities} and
 * {@code merchantId} claims for authorization decisions, so every endpoint
 * applies the same rule. Copied from identity-service, plus the merchant checks.
 */
public final class TokenCapabilities {

    private static final String CAPABILITIES = "capabilities";
    private static final String MERCHANT_ID = "merchantId";

    private TokenCapabilities() {
    }

    public static boolean isAdmin(Jwt jwt) {
        return has(jwt, Capability.ADMIN);
    }

    /** Throws {@link AdminRequiredException} (403) unless the token carries ADMIN. */
    public static void requireAdmin(Jwt jwt) {
        if (!isAdmin(jwt)) {
            throw new AdminRequiredException();
        }
    }

    /**
     * True only if the token carries MERCHANT and its {@code merchantId} claim
     * equals {@code merchantId}. Merchant data is scoped from the token, never
     * from request parameters (DESIGN.md Section 2, Tokens).
     */
    public static boolean isMerchant(Jwt jwt, long merchantId) {
        if (!has(jwt, Capability.MERCHANT)) {
            return false;
        }
        Object claim = jwt.getClaim(MERCHANT_ID);
        return claim instanceof Number number && number.longValue() == merchantId;
    }

    private static boolean has(Jwt jwt, Capability capability) {
        List<String> capabilities = jwt.getClaimAsStringList(CAPABILITIES);
        return capabilities != null && capabilities.contains(capability.name());
    }
}
