package com.basketry.identity.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.identity.auth.Capability;
import com.basketry.identity.auth.TokenIssuer;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * GET /accounts/{id} authorization (DESIGN.md Section 3): self or Admin, and an
 * ADMIN token skips the sub comparison entirely. Tokens come from the real
 * POST /auth/login unless a test needs a specific sub.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GetAccountTests {

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
    private TokenIssuer tokenIssuer;

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

    // --- own account -------------------------------------------------------

    @Test
    void buyerCanReadOwnAccount() throws Exception {
        getAccount(alice.getId(), login("alice@example.com", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alice.getId()))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.buyerActive").value(true))
                .andExpect(jsonPath("$.merchantId").isEmpty())
                .andExpect(jsonPath("$.merchantActive").value(false))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void merchantCanReadOwnAccount() throws Exception {
        jdbc.update("UPDATE account SET merchant_id = 42, merchant_active = TRUE WHERE id = ?", alice.getId());

        getAccount(alice.getId(), login("alice@example.com", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value(42))
                .andExpect(jsonPath("$.merchantActive").value(true));
    }

    @Test
    void deactivatedBuyerWithValidTokenCanStillReadOwnAccount() throws Exception {
        // Token issued while active; deactivation does not revoke it (DESIGN.md Section 2, Tokens).
        String token = login("alice@example.com", PASSWORD);
        jdbc.update("UPDATE account SET buyer_active = FALSE WHERE id = ?", alice.getId());

        // Must answer 200 with the live buyerActive = false, not 403: order-service reads
        // this field at checkout to refuse a buyer deactivated after their token was issued.
        getAccount(alice.getId(), token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyerActive").value(false));
    }

    // --- other account -----------------------------------------------------

    @Test
    void buyerCannotReadAnotherAccount() throws Exception {
        getAccount(bob.getId(), login("alice@example.com", PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void merchantCannotReadAnotherAccount() throws Exception {
        jdbc.update("UPDATE account SET merchant_id = 42, merchant_active = TRUE WHERE id = ?", alice.getId());

        getAccount(bob.getId(), login("alice@example.com", PASSWORD)).andExpect(status().isForbidden());
    }

    @Test
    void buyerGets403Not404ForAnIdThatDoesNotExist() throws Exception {
        // Same answer as for an existing account, so ids cannot be probed.
        getAccount(Long.MAX_VALUE, login("alice@example.com", PASSWORD)).andExpect(status().isForbidden());
    }

    // --- admin -------------------------------------------------------------

    @Test
    void adminCanReadAnyAccount() throws Exception {
        String adminToken = login(ADMIN_EMAIL, ADMIN_PASSWORD);

        getAccount(alice.getId(), adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"));
        getAccount(bob.getId(), adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("bob@example.com"));
    }

    @Test
    void adminSubIsNeverComparedWithThePathId() throws Exception {
        // Simulates the id collision: an admin whose sub equals alice's account id.
        String adminWithAlicesId = tokenIssuer.issue(alice.getId(), List.of(Capability.ADMIN), null);

        getAccount(alice.getId(), adminWithAlicesId).andExpect(status().isOk());
        getAccount(bob.getId(), adminWithAlicesId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("bob@example.com"));
    }

    @Test
    void adminGets404ForAnIdThatDoesNotExist() throws Exception {
        getAccount(Long.MAX_VALUE, login(ADMIN_EMAIL, ADMIN_PASSWORD))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // --- no or bad token ---------------------------------------------------

    @Test
    void requestWithoutTokenGets401() throws Exception {
        mockMvc.perform(get("/accounts/{id}", alice.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void requestWithInvalidTokenGets401() throws Exception {
        getAccount(alice.getId(), "not-a-jwt").andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithTamperedSignatureGets401() throws Exception {
        String token = login("alice@example.com", PASSWORD);
        String tampered = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");

        getAccount(alice.getId(), tampered).andExpect(status().isUnauthorized());
    }

    // --- helpers -----------------------------------------------------------

    private ResultActions getAccount(long id, String token) throws Exception {
        return mockMvc.perform(get("/accounts/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
