package com.basketry.identity.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** Signs access tokens with the claims set out in DESIGN.md Section 2, Tokens. */
@Component
public class TokenIssuer {

    /** One hour, and there are no refresh tokens: the user logs in again on expiry. */
    public static final Duration TOKEN_LIFETIME = Duration.ofHours(1);

    private final JwtEncoder jwtEncoder;

    public TokenIssuer(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    /**
     * @param merchantId included only when {@code capabilities} contains MERCHANT,
     *                   so services scope merchant data from the token
     */
    public String issue(long subject, List<Capability> capabilities, Long merchantId) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(Long.toString(subject))
                .issuedAt(now)
                .expiresAt(now.plus(TOKEN_LIFETIME))
                .claim("capabilities", capabilities.stream().map(Capability::name).toList());
        if (capabilities.contains(Capability.MERCHANT)) {
            claims.claim("merchantId", merchantId);
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
