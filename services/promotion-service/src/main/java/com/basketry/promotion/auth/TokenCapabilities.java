package com.basketry.promotion.auth;

import java.util.List;
import java.util.OptionalLong;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The one place that reads the JWT's {@code capabilities} and
 * {@code merchantId} claims, so every endpoint applies the same rule. Copied
 * from inventory-service, with the merchant id a whole number above 0. Merchant
 * data is scoped from the token, never from request parameters (DESIGN.md
 * Section 2, Tokens).
 */
public final class TokenCapabilities {

    private static final String CAPABILITIES = "capabilities";
    private static final String MERCHANT_ID = "merchantId";

    private TokenCapabilities() {
    }

    public static boolean isAdmin(Jwt jwt) {
        return has(jwt, "ADMIN");
    }

    /**
     * The merchant the token speaks for: present only with the MERCHANT
     * capability and a {@code merchantId} claim that is a whole number above 0.
     */
    public static OptionalLong merchantId(Jwt jwt) {
        if (!has(jwt, "MERCHANT")) {
            return OptionalLong.empty();
        }
        Object claim = jwt.getClaim(MERCHANT_ID);
        if ((claim instanceof Long || claim instanceof Integer) && ((Number) claim).longValue() > 0) {
            return OptionalLong.of(((Number) claim).longValue());
        }
        return OptionalLong.empty();
    }

    private static boolean has(Jwt jwt, String capability) {
        List<String> capabilities = jwt.getClaimAsStringList(CAPABILITIES);
        return capabilities != null && capabilities.contains(capability);
    }
}
