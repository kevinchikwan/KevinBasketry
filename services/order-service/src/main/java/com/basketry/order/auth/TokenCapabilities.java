package com.basketry.order.auth;

import java.util.List;
import java.util.OptionalLong;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The one place that reads the JWT's {@code sub}, {@code capabilities} and
 * {@code merchantId} claims, so every endpoint applies the same rule. Copied
 * from catalog-service, plus BUYER and the account. Merchant data is scoped
 * from the token, never from request parameters (DESIGN.md Section 2, Tokens).
 *
 * <p>The token's merchantId serves the reads only. Checkout's OWN_PRODUCT rule
 * uses identity-service's live link instead (DESIGN.md Section 2,
 * order-service): a token issued before a link change can be an hour old.
 */
public final class TokenCapabilities {

    private static final String CAPABILITIES = "capabilities";
    private static final String MERCHANT_ID = "merchantId";

    private TokenCapabilities() {
    }

    public static boolean isAdmin(Jwt jwt) {
        return has(jwt, "ADMIN");
    }

    public static boolean isBuyer(Jwt jwt) {
        return has(jwt, "BUYER");
    }

    /**
     * The account the token speaks for: its {@code sub}, which identity-service
     * always issues as a positive number.
     *
     * @throws InvalidTokenSubjectException otherwise
     */
    public static long accountId(Jwt jwt) {
        String subject = jwt.getSubject();
        try {
            long id = Long.parseLong(subject == null ? "" : subject);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException notANumber) {
            // Below.
        }
        throw new InvalidTokenSubjectException();
    }

    /** The merchant the token speaks for: present only with the MERCHANT capability and a numeric claim. */
    public static OptionalLong merchantId(Jwt jwt) {
        if (!has(jwt, "MERCHANT")) {
            return OptionalLong.empty();
        }
        Object claim = jwt.getClaim(MERCHANT_ID);
        return claim instanceof Number number ? OptionalLong.of(number.longValue()) : OptionalLong.empty();
    }

    private static boolean has(Jwt jwt, String capability) {
        List<String> capabilities = jwt.getClaimAsStringList(CAPABILITIES);
        return capabilities != null && capabilities.contains(capability);
    }

    /** A token whose sub is not a positive number: identity-service never issues one, so it is treated as invalid. */
    public static class InvalidTokenSubjectException extends RuntimeException {

        InvalidTokenSubjectException() {
            super("The token's subject is not an account id.");
        }
    }
}
