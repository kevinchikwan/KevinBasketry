package com.basketry.inventory.auth;

import java.util.List;
import java.util.OptionalLong;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The one place that reads the JWT's {@code capabilities} and
 * {@code merchantId} claims, so every endpoint applies the same rule. Copied
 * from merchant-service. Merchant data is scoped from the token, never from
 * request parameters (DESIGN.md Section 2, Tokens).
 */
public final class TokenCapabilities {

    private static final String CAPABILITIES = "capabilities";
    private static final String MERCHANT_ID = "merchantId";

    private TokenCapabilities() {
    }

    public static boolean isAdmin(Jwt jwt) {
        return has(jwt, "ADMIN");
    }

    /** The merchant the token speaks for: present only with the MERCHANT capability and a numeric claim. */
    public static OptionalLong merchantId(Jwt jwt) {
        if (!has(jwt, "MERCHANT")) {
            return OptionalLong.empty();
        }
        Object claim = jwt.getClaim(MERCHANT_ID);
        return claim instanceof Number number ? OptionalLong.of(number.longValue()) : OptionalLong.empty();
    }

    /** True only if the token carries MERCHANT and its merchantId claim equals {@code merchantId}. */
    public static boolean isMerchant(Jwt jwt, long merchantId) {
        OptionalLong own = merchantId(jwt);
        return own.isPresent() && own.getAsLong() == merchantId;
    }

    private static boolean has(Jwt jwt, String capability) {
        List<String> capabilities = jwt.getClaimAsStringList(CAPABILITIES);
        return capabilities != null && capabilities.contains(capability);
    }
}
