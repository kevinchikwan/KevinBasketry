package com.basketry.identity.auth;

import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The one place that reads the JWT {@code capabilities} claim for
 * authorization decisions, so every endpoint applies the same rule.
 */
public final class TokenCapabilities {

    private static final String CLAIM = "capabilities";

    private TokenCapabilities() {
    }

    public static boolean isAdmin(Jwt jwt) {
        List<String> capabilities = jwt.getClaimAsStringList(CLAIM);
        return capabilities != null && capabilities.contains(Capability.ADMIN.name());
    }

    /** Throws {@link AdminRequiredException} (403) unless the token carries ADMIN. */
    public static void requireAdmin(Jwt jwt) {
        if (!isAdmin(jwt)) {
            throw new AdminRequiredException();
        }
    }
}
