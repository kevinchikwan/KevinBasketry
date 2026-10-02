package com.basketry.identity.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The RS256 key pair (DESIGN.md Section 2, Tokens). identity-service is the
 * only holder of the private key; the public half is published at
 * GET /.well-known/jwks.json.
 *
 * <p>Early development: the key pair is generated at startup and lives only in
 * memory, so restarting the service invalidates every issued token. The
 * deployed version loads the private key from a Kubernetes Secret instead.
 */
@Configuration
public class JwtKeyConfig {

    @Bean
    RSAKey jwtSigningKey() throws JOSEException {
        return new RSAKeyGenerator(2048)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyID(UUID.randomUUID().toString())
                .generate();
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(jwtSigningKey)));
    }

    /** Verifies tokens with the public key only, the same way the other services will. */
    @Bean
    JwtDecoder jwtDecoder(RSAKey jwtSigningKey) throws JOSEException {
        return NimbusJwtDecoder.withPublicKey(jwtSigningKey.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
    }
}
