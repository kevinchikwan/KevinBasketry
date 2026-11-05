package com.basketry.merchant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

/**
 * Verifies identity-service's RS256 tokens with the public keys it publishes
 * at GET /.well-known/jwks.json (DESIGN.md Section 2, Tokens).
 *
 * <ul>
 *   <li>The JWKS is fetched on the first request that needs it, not at
 *       startup, so the two services can start in any order.</li>
 *   <li>Keys are cached. A token whose {@code kid} is not in the cache makes
 *       the decoder fetch the JWKS again. In local dev identity-service makes
 *       a new key (new {@code kid}) on every restart, so new tokens work at
 *       once and tokens signed with the old key get 401.</li>
 *   <li>The fetch uses our own timeouts, not the 30-second default, so an
 *       identity-service that hangs fails a request quickly with 503
 *       (TokenVerificationFailureHandler).</li>
 * </ul>
 */
@Configuration
public class JwtDecoderConfig {

    @Bean
    JwtDecoder jwtDecoder(IdentityServiceProperties identity) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(identity.connectTimeout());
        requestFactory.setReadTimeout(identity.readTimeout());
        return NimbusJwtDecoder.withJwkSetUri(identity.jwksUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(requestFactory))
                .build();
    }
}
