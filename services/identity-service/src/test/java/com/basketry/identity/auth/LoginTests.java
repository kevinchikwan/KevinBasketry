package com.basketry.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.identity.account.Account;
import com.basketry.identity.account.AccountRegistrationService;
import com.basketry.identity.account.AccountRepository;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * POST /auth/login and GET /.well-known/jwks.json through the full stack.
 * Every issued token is verified the way the other services will verify it:
 * with only the public key fetched from the JWKS endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoginTests {

    private static final String PASSWORD = "Corr3ct-Horse!";

    // Seeded by V2__seed_initial_admin.sql.
    private static final String ADMIN_EMAIL = "admin-placeholder@basketry.example";
    private static final String ADMIN_PASSWORD = "ChangeMe-Before-Deploy!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountRegistrationService registrationService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void clearAccounts() {
        accounts.deleteAll();
    }

    // --- successful logins -------------------------------------------------

    @Test
    void buyerLoginReturnsSignedJwtWithBuyerCapability() throws Exception {
        Account buyer = registrationService.registerBuyer("buyer@example.com", PASSWORD);

        String body = login("buyer@example.com", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andReturn().getResponse().getContentAsString();

        Jwt jwt = verifyWithPublishedKey(JsonPath.read(body, "$.accessToken"));
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256");
        assertThat(jwt.getSubject()).isEqualTo(buyer.getId().toString());
        assertThat(jwt.getClaimAsStringList("capabilities")).containsExactly("BUYER");
        assertThat(jwt.hasClaim("merchantId")).isFalse();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void emailIsMatchedCaseInsensitively() throws Exception {
        registrationService.registerBuyer("buyer@example.com", PASSWORD);

        login("  Buyer@EXAMPLE.com ", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void activeMerchantLinkAddsMerchantCapabilityAndMerchantId() throws Exception {
        Account account = registrationService.registerBuyer("seller@example.com", PASSWORD);
        linkMerchant(account, 42L, true);

        Jwt jwt = loginAndVerify("seller@example.com", PASSWORD);
        assertThat(jwt.getSubject()).isEqualTo(account.getId().toString());
        assertThat(jwt.getClaimAsStringList("capabilities")).containsExactly("BUYER", "MERCHANT");
        assertThat(jwt.<Number>getClaim("merchantId").longValue()).isEqualTo(42L);
    }

    @Test
    void inactiveMerchantLinkGivesBuyerOnlyAndNoMerchantId() throws Exception {
        Account account = registrationService.registerBuyer("seller@example.com", PASSWORD);
        linkMerchant(account, 42L, false);

        Jwt jwt = loginAndVerify("seller@example.com", PASSWORD);
        assertThat(jwt.getClaimAsStringList("capabilities")).containsExactly("BUYER");
        assertThat(jwt.hasClaim("merchantId")).isFalse();
    }

    @Test
    void adminLoginFallsBackToAdminAccountAndGetsAdminOnly() throws Exception {
        Long adminId = jdbc.queryForObject("SELECT id FROM admin_account WHERE email = ?", Long.class, ADMIN_EMAIL);

        Jwt jwt = loginAndVerify(ADMIN_EMAIL, ADMIN_PASSWORD);
        assertThat(jwt.getSubject()).isEqualTo(adminId.toString());
        assertThat(jwt.getClaimAsStringList("capabilities")).containsExactly("ADMIN");
        assertThat(jwt.hasClaim("merchantId")).isFalse();
    }

    @Test
    void loginDoesNotReapplyPasswordPolicy() throws Exception {
        // A password set before the current policy (REQUIREMENTS.md Section 4) still works.
        String legacyPassword = "no-uppercase-or-digit-and-too-long";
        jdbc.update("INSERT INTO account (email, password_hash, buyer_active, merchant_active, created_at) "
                        + "VALUES (?, ?, TRUE, FALSE, CURRENT_TIMESTAMP)",
                "legacy@example.com", passwordEncoder.encode(legacyPassword));

        Jwt jwt = loginAndVerify("legacy@example.com", legacyPassword);
        assertThat(jwt.getClaimAsStringList("capabilities")).containsExactly("BUYER");
    }

    // --- rejected logins ---------------------------------------------------

    @Test
    void wrongPasswordIsRejectedWith401() throws Exception {
        registrationService.registerBuyer("buyer@example.com", PASSWORD);

        login("buyer@example.com", "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void wrongAdminPasswordIsRejectedWith401() throws Exception {
        login(ADMIN_EMAIL, "wrong-password").andExpect(status().isUnauthorized());
    }

    @Test
    void unknownEmailIsRejectedWithSameResponseAsWrongPassword() throws Exception {
        registrationService.registerBuyer("buyer@example.com", PASSWORD);

        String wrongPassword = login("buyer@example.com", "wrong-password")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = login("nobody@example.com", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Same body, so the endpoint cannot be used to discover registered emails.
        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    void deactivatedBuyerIsRejectedWith403() throws Exception {
        Account account = registrationService.registerBuyer("buyer@example.com", PASSWORD);
        deactivateBuyer(account);

        login("buyer@example.com", PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void deactivatedBuyerIsRejectedEvenWithActiveMerchantLink() throws Exception {
        Account account = registrationService.registerBuyer("seller@example.com", PASSWORD);
        linkMerchant(account, 42L, true);
        deactivateBuyer(account);

        login("seller@example.com", PASSWORD).andExpect(status().isForbidden());
    }

    @Test
    void deactivatedBuyerWithWrongPasswordGets401Not403() throws Exception {
        Account account = registrationService.registerBuyer("buyer@example.com", PASSWORD);
        deactivateBuyer(account);

        // Account status is only revealed to a caller who knows the password.
        login("buyer@example.com", "wrong-password").andExpect(status().isUnauthorized());
    }

    @Test
    void passwordLongerThan72BytesIsRejectedWith401Not500() throws Exception {
        registrationService.registerBuyer("buyer@example.com", PASSWORD);

        login("buyer@example.com", PASSWORD + "x".repeat(72)).andExpect(status().isUnauthorized());
    }

    @Test
    void missingFieldsAreRejectedWith400() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "buyer@example.com"}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password": "%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isBadRequest());
    }

    // --- JWKS --------------------------------------------------------------

    @Test
    void jwksIsPublicAndContainsOnlyThePublicKey() throws Exception {
        String body = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        RSAKey key = (RSAKey) JWKSet.parse(body).getKeys().getFirst();
        assertThat(key.isPrivate()).isFalse();
        assertThat(key.getKeyID()).isNotBlank();
        assertThat(key.getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(body).doesNotContain("\"d\"").doesNotContain("\"p\"").doesNotContain("\"q\"");
    }

    // --- helpers -----------------------------------------------------------

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, password)));
    }

    private Jwt loginAndVerify(String email, String password) throws Exception {
        String body = login(email, password)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return verifyWithPublishedKey(JsonPath.read(body, "$.accessToken"));
    }

    /** Fetches the JWKS and checks the token's signature and expiry with it. */
    private Jwt verifyWithPublishedKey(String token) throws Exception {
        String jwks = mockMvc.perform(get("/.well-known/jwks.json"))
                .andReturn().getResponse().getContentAsString();
        RSAKey publicKey = (RSAKey) JWKSet.parse(jwks).getKeys().getFirst();
        return NimbusJwtDecoder.withPublicKey(publicKey.toRSAPublicKey()).build().decode(token);
    }

    /** No write API exists for this yet (it arrives with merchant-link), so set it directly. */
    private void linkMerchant(Account account, long merchantId, boolean merchantActive) {
        jdbc.update("UPDATE account SET merchant_id = ?, merchant_active = ? WHERE id = ?",
                merchantId, merchantActive, account.getId());
    }

    /** No write API exists for this yet (PATCH /accounts/{id}/buyer-status), so set it directly. */
    private void deactivateBuyer(Account account) {
        jdbc.update("UPDATE account SET buyer_active = FALSE WHERE id = ?", account.getId());
    }
}
