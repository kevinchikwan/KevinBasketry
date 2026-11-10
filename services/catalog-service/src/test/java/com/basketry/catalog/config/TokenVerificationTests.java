package com.basketry.catalog.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.catalog.support.CatalogServiceTestBase;
import com.basketry.catalog.support.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tokens are verified against identity-service's JWKS (DESIGN.md Section 2,
 * Tokens): a good one passes, anything else is 401. Copied from
 * inventory-service, on GET /manage/products. Mutation: a JwtDecoder that skips
 * the signature or the expiry check.
 */
class TokenVerificationTests extends CatalogServiceTestBase {

    @Test
    void aValidTokenPasses() throws Exception {
        perform(bearer(get("/manage/products"), TestTokens.merchant(10, MERCHANT))).andExpect(status().isOk());
    }

    @Test
    void anExpiredTokenIs401() throws Exception {
        perform(bearer(get("/manage/products"), TestTokens.expiredMerchant(10, MERCHANT))).andExpect(status().isUnauthorized());
    }

    @Test
    void aTamperedTokenIs401() throws Exception {
        String[] parts = TestTokens.admin().split("\\.");
        String forged = parts[0] + "." + TestTokens.merchant(10, MERCHANT).split("\\.")[1] + "." + parts[2];

        perform(bearer(get("/manage/products"), forged)).andExpect(status().isUnauthorized());
    }

    /** Signed with a key identity-service does not publish (for example before it restarted). */
    @Test
    void aTokenSignedWithAnUnpublishedKeyIs401() throws Exception {
        String foreign = TestTokens.sign(TestTokens.newKey(), 1, List.of("ADMIN"), null,
                Instant.now().plus(Duration.ofHours(1)));

        perform(bearer(get("/manage/products"), foreign)).andExpect(status().isUnauthorized());
    }

    @Test
    void aMalformedOrMissingTokenIs401() throws Exception {
        perform(bearer(get("/manage/products"), "not-a-jwt")).andExpect(status().isUnauthorized());
        perform(get("/manage/products")).andExpect(status().isUnauthorized());
    }
}
