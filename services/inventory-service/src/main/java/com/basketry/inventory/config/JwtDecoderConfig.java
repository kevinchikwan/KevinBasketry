package com.basketry.inventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

/**
 * Verifies identity-service's RS256 tokens with the public keys it publishes
 * at GET /.well-known/jwks.json (DESIGN.md Section 2, Tokens), as
 * merchant-service does: fetched on first use (the services start in any
 * order), cached, fetched again for an unknown {@code kid}, and with our own
 * timeouts, so a hanging identity-service fails a request quickly with 503.
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
