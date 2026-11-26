package com.basketry.promotion.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signs tokens the way identity-service does (copied from inventory-service):
 * RS256, a {@code kid}, {@code sub}, {@code capabilities}, and
 * {@code merchantId} with MERCHANT. {@link #KEY} is what
 * {@link FakeIdentityServer#shared()} publishes.
 */
public final class TestTokens {

    public static final RSAKey KEY = newKey();

    public static final long ADMIN_ID = 1;

    private TestTokens() {
    }

    public static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256)
                    .keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String admin() {
        return sign(KEY, ADMIN_ID, List.of("ADMIN"), null, Instant.now().plus(Duration.ofHours(1)));
    }

    public static String buyer(long accountId) {
        return sign(KEY, accountId, List.of("BUYER"), null, Instant.now().plus(Duration.ofHours(1)));
    }

    public static String merchant(long accountId, long merchantId) {
        return sign(KEY, accountId, List.of("BUYER", "MERCHANT"), merchantId, Instant.now().plus(Duration.ofHours(1)));
    }

    public static String expiredMerchant(long accountId, long merchantId) {
        return sign(KEY, accountId, List.of("BUYER", "MERCHANT"), merchantId, Instant.now().minus(Duration.ofHours(1)));
    }

    /** {@code merchantId} is included whenever it is non-null, so tests can also build odd tokens. */
    public static String sign(RSAKey key, long subject, List<String> capabilities, Long merchantId, Instant expiresAt) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(Long.toString(subject))
                .issuedAt(expiresAt.minus(Duration.ofHours(1)))
                .expiresAt(expiresAt)
                .claim("capabilities", capabilities);
        if (merchantId != null) {
            claims.claim("merchantId", merchantId);
        }
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),
                claims.build())).getTokenValue();
    }
}
