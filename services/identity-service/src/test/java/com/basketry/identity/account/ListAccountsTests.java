package com.basketry.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
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
 * GET /accounts (FR-A5): Admin only, paginated with page from 0, size 1..100
 * (default 20), fixed id-ascending order.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListAccountsTests {

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
    void adminGetsFirstPageWithAccountFieldsAndNoPasswordHash() throws Exception {
        jdbc.update("UPDATE account SET merchant_id = 42, merchant_active = TRUE WHERE id = ?", bob.getId());

        list(adminToken(), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(alice.getId()))
                .andExpect(jsonPath("$.content[0].email").value("alice@example.com"))
                .andExpect(jsonPath("$.content[0].buyerActive").value(true))
                .andExpect(jsonPath("$.content[0].merchantId").isEmpty())
                .andExpect(jsonPath("$.content[0].merchantActive").value(false))
                .andExpect(jsonPath("$.content[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.content[1].id").value(bob.getId()))
                .andExpect(jsonPath("$.content[1].merchantId").value(42))
                .andExpect(jsonPath("$.content[1].merchantActive").value(true))
                .andExpect(jsonPath("$..passwordHash").isEmpty());
    }

    @Test
    void adminAccountsAreNotListed() throws Exception {
        list(adminToken(), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.email == '" + ADMIN_EMAIL + "')]").isEmpty());
    }

    @Test
    void pagesAreInStableIdOrderWithoutOverlap() throws Exception {
        for (int i = 0; i < 5; i++) {
            registrationService.registerBuyer("buyer" + i + "@example.com", PASSWORD);
        }
        String token = adminToken();
        List<Integer> seen = new ArrayList<>();

        for (int page = 0; page < 3; page++) {
            String body = list(token, "?page=" + page + "&size=3")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(page))
                    .andExpect(jsonPath("$.size").value(3))
                    .andExpect(jsonPath("$.totalElements").value(7))
                    .andExpect(jsonPath("$.totalPages").value(3))
                    .andReturn().getResponse().getContentAsString();
            seen.addAll(JsonPath.read(body, "$.content[*].id"));
        }

        List<Integer> expected = accounts.findAll().stream()
                .map(a -> a.getId().intValue()).sorted().toList();
        assertThat(seen).containsExactlyElementsOf(expected);
    }

    @Test
    void pagePastTheEndIsEmpty() throws Exception {
        list(adminToken(), "?page=5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void maximumPageSizeIsAccepted() throws Exception {
        list(adminToken(), "?size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void outOfRangePagingIsRejectedWith400() throws Exception {
        String token = adminToken();
        for (String query : List.of("?size=101", "?size=0", "?size=-1", "?page=-1", "?page=abc", "?size=abc")) {
            list(token, query)
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }

    // --- refused -----------------------------------------------------------

    @Test
    void buyerTokenGets403() throws Exception {
        list(login("alice@example.com", PASSWORD), "")
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    @Test
    void merchantTokenGets403() throws Exception {
        jdbc.update("UPDATE account SET merchant_id = 42, merchant_active = TRUE WHERE id = ?", alice.getId());

        list(login("alice@example.com", PASSWORD), "").andExpect(status().isForbidden());
    }

    @Test
    void requestWithoutTokenGets401() throws Exception {
        mockMvc.perform(get("/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.content").doesNotExist());
    }

    @Test
    void requestWithInvalidTokenGets401() throws Exception {
        list("not-a-jwt", "").andExpect(status().isUnauthorized());
    }

    // --- helpers -----------------------------------------------------------

    private ResultActions list(String token, String query) throws Exception {
        return mockMvc.perform(get("/accounts" + query).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private String adminToken() throws Exception {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD);
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
