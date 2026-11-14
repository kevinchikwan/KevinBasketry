package com.basketry.order.support;

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
 * Signs tokens the way identity-service does (copied from catalog-service):
 * RS256, a {@code kid}, {@code sub}, {@code capabilities}, and
 * {@code merchantId} with MERCHANT. {@link #KEY} is what
 * {@link FakeServices#shared()} publishes at /.well-known/jwks.json.
 */
public final class TestTokens {

    public static final RSAKey KEY = newKey();

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
        return sign("1", List.of("ADMIN"), null);
    }

    public static String buyer(long accountId) {
        return sign(Long.toString(accountId), List.of("BUYER"), null);
    }

    /** An account linked to an active merchant: BUYER and MERCHANT, with the merchantId claim. */
    public static String merchant(long accountId, long merchantId) {
        return sign(Long.toString(accountId), List.of("BUYER", "MERCHANT"), merchantId);
    }

    /** {@code merchantId} is included whenever it is non-null, so tests can also build odd tokens. */
    public static String sign(String subject, List<String> capabilities, Long merchantId) {
        Instant expiresAt = Instant.now().plus(Duration.ofHours(1));
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(subject)
                .issuedAt(expiresAt.minus(Duration.ofHours(1)))
                .expiresAt(expiresAt)
                .claim("capabilities", capabilities);
        if (merchantId != null) {
            claims.claim("merchantId", merchantId);
        }
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(KEY)));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),
                claims.build())).getTokenValue();
    }
}
