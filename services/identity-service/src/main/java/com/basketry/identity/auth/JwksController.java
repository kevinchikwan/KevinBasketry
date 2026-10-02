package com.basketry.identity.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the RS256 verification key that every other service uses to check tokens. */
@RestController
public class JwksController {

    private final Map<String, Object> publicJwkSet;

    public JwksController(RSAKey jwtSigningKey) {
        // toPublicJWK() drops the private exponent and CRT parameters.
        this.publicJwkSet = new JWKSet(jwtSigningKey.toPublicJWK()).toJSONObject();
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return publicJwkSet;
    }
}
