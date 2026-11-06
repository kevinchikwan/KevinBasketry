package com.basketry.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * PATCH /accounts/{id}/buyer-status (FR-A6): Admin only. The admin check runs
 * before the lookup, so a non-admin gets 403 even for an unknown id.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BuyerStatusTests {

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

    private Account alice;
    private Account bob;

    @BeforeEach
    void setUp() {
        accounts.deleteAll();
        alice = registrationService.registerBuyer("alice@example.com", PASSWORD);
        bob = registrationService.registerBuyer("bob@example.com", PASSWORD);
    }

    // --- admin -------------------------------------------------------------

    @Test
    void adminCanDeactivateAndReactivateBuyer() throws Exception {
        String token = adminToken();

        patchStatus(alice.getId(), token, "{\"buyerActive\": false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alice.getId()))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.buyerActive").value(false))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        assertThat(buyerActiveInDb(alice.getId())).isFalse();
        assertThat(buyerActiveInDb(bob.getId())).isTrue();
        mockMvc.perform(get("/accounts/{id}", alice.getId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.buyerActive").value(false));

        patchStatus(alice.getId(), token, "{\"buyerActive\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyerActive").value(true));
        assertThat(buyerActiveInDb(alice.getId())).isTrue();
    }

    @Test
    void settingTheCurrentValueAgainIsAllowed() throws Exception {
        patchStatus(alice.getId(), adminToken(), "{\"buyerActive\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyerActive").value(true));
    }

    @Test
    void deactivatedBuyerCannotLogIn() throws Exception {
        patchStatus(alice.getId(), adminToken(), "{\"buyerActive\": false}").andExpect(status().isOk());

        loginRequest("alice@example.com", PASSWORD).andExpect(status().isForbidden());
    }

    @Test
    void reactivatedBuyerCanLogInAgain() throws Exception {
        String token = adminToken();
        patchStatus(alice.getId(), token, "{\"buyerActive\": false}").andExpect(status().isOk());
        patchStatus(alice.getId(), token, "{\"buyerActive\": true}").andExpect(status().isOk());

        loginRequest("alice@example.com", PASSWORD).andExpect(status().isOk());
    }

    /**
     * DESIGN.md Section 2, login rules: buyerActive decides whether the account
     * can log in at all; a deactivated buyer cannot log in anywhere, including
     * the Merchant portal, whatever merchantActive says.
     */
    @Test
    void deactivatedBuyerWithActiveMerchantLinkCannotLogIn() throws Exception {
        jdbc.update("UPDATE account SET merchant_id = 42, merchant_active = TRUE WHERE id = ?", alice.getId());

        patchStatus(alice.getId(), adminToken(), "{\"buyerActive\": false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyerActive").value(false))
                // The merchant link is left exactly as it was.
                .andExpect(jsonPath("$.merchantId").value(42))
                .andExpect(jsonPath("$.merchantActive").value(true));

        loginRequest("alice@example.com", PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void adminGets404ForUnknownId() throws Exception {
        patchStatus(Long.MAX_VALUE, adminToken(), "{\"buyerActive\": false}")
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // --- bad body: must be 400 and must never deactivate ---------------------

    @Test
    void emptyBodyObjectIsRejectedAndDoesNotDeactivate() throws Exception {
        patchStatus(alice.getId(), adminToken(), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(buyerActiveInDb(alice.getId())).isTrue();
    }

    @Test
    void nullBuyerActiveIsRejectedAndDoesNotDeactivate() throws Exception {
        patchStatus(alice.getId(), adminToken(), "{\"buyerActive\": null}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(buyerActiveInDb(alice.getId())).isTrue();
    }

    @Test
    void nonBooleanOrMissingBodyIsRejected() throws Exception {
        String token = adminToken();
        patchStatus(alice.getId(), token, "{\"buyerActive\": \"maybe\"}").andExpect(status().isBadRequest());
        patchStatus(alice.getId(), token, "not json").andExpect(status().isBadRequest());
        mockMvc.perform(patch("/accounts/{id}/buyer-status", alice.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());
        assertThat(buyerActiveInDb(alice.getId())).isTrue();
    }

    /**
     * Only a real JSON boolean counts, the same rule as merchant-service: each
     * of these is refused, and neither value is applied. Both directions are
     * tried, from a buyer who is active and from one who is not, so a coerced
     * value could not go unnoticed by matching the stored one.
     */
    @ParameterizedTest
    @ValueSource(strings = {"\"false\"", "\"true\"", "\"FALSE\"", "\"\"", "0", "1", "0.0", "1.0", "[true]"})
    void onlyARealJsonBooleanIsAccepted(String value) throws Exception {
        String token = adminToken();
        String body = "{\"buyerActive\": " + value + "}";

        patchStatus(alice.getId(), token, body)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(buyerActiveInDb(alice.getId())).isTrue();

        jdbc.update("UPDATE account SET buyer_active = FALSE WHERE id = ?", alice.getId());
        patchStatus(alice.getId(), token, body).andExpect(status().isBadRequest());
        assertThat(buyerActiveInDb(alice.getId())).isFalse();
    }

    // --- refused -----------------------------------------------------------

    @Test
    void buyerTokenGets403ForAnotherAccount() throws Exception {
        patchStatus(bob.getId(), tokenFor("alice@example.com", PASSWORD), "{\"buyerActive\": false}")
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(buyerActiveInDb(bob.getId())).isTrue();
    }

    @Test
    void buyerTokenGets403ForOwnAccount() throws Exception {
        patchStatus(alice.getId(), tokenFor("alice@example.com", PASSWORD), "{\"buyerActive\": false}")
                .andExpect(status().isForbidden());
        assertThat(buyerActiveInDb(alice.getId())).isTrue();
    }

    @Test
    void buyerTokenGets403Not404ForUnknownId() throws Exception {
        patchStatus(Long.MAX_VALUE, tokenFor("alice@example.com", PASSWORD), "{\"buyerActive\": false}")
                .andExpect(status().isForbidden());
    }

    @Test
    void merchantTokenGets403() throws Exception {
        jdbc.update("UPDATE account SET merchant_id = 42, merchant_active = TRUE WHERE id = ?", alice.getId());

        patchStatus(bob.getId(), tokenFor("alice@example.com", PASSWORD), "{\"buyerActive\": false}")
                .andExpect(status().isForbidden());
        assertThat(buyerActiveInDb(bob.getId())).isTrue();
    }

    @Test
    void requestWithoutTokenGets401() throws Exception {
        mockMvc.perform(patch("/accounts/{id}/buyer-status", alice.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"buyerActive\": false}"))
                .andExpect(status().isUnauthorized());
        assertThat(buyerActiveInDb(alice.getId())).isTrue();
    }

    // --- helpers -----------------------------------------------------------

    private ResultActions patchStatus(long id, String token, String body) throws Exception {
        return mockMvc.perform(patch("/accounts/{id}/buyer-status", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private boolean buyerActiveInDb(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT buyer_active FROM account WHERE id = ?", Boolean.class, id));
    }

    private String adminToken() throws Exception {
        return tokenFor(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    private String tokenFor(String email, String password) throws Exception {
        String body = loginRequest(email, password)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private ResultActions loginRequest(String email, String password) throws Exception {
        return mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, password)));
    }
}
