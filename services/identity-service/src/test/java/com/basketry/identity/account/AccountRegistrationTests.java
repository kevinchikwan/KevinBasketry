package com.basketry.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * POST /accounts/register through the full stack: security filter chain,
 * validation, service, and the H2 database migrated by Flyway.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AccountRegistrationTests {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void clearAccounts() {
        accounts.deleteAll();
    }

    @Test
    void registersBuyerAndReturns201WithLocationAndNoBody() throws Exception {
        MvcResult result = register("""
                {"email": "buyer@example.com", "password": "%s"}
                """.formatted(PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(content().string(""))
                .andReturn();

        Account account = accounts.findAll().getFirst();
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("http://localhost/accounts/" + account.getId());
        assertThat(account.getEmail()).isEqualTo("buyer@example.com");
        assertThat(account.isBuyerActive()).isTrue();
        assertThat(account.getMerchantId()).isNull();
        assertThat(account.isMerchantActive()).isFalse();
        assertThat(account.getCreatedAt()).isNotNull();
    }

    @Test
    void storesBcryptHashNotPlaintext() throws Exception {
        register("""
                {"email": "buyer@example.com", "password": "%s"}
                """.formatted(PASSWORD))
                .andExpect(status().isCreated());

        String hash = accounts.findAll().getFirst().getPasswordHash();
        assertThat(hash).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
    }

    @Test
    void normalizesEmailToLowercase() throws Exception {
        register("""
                {"email": "Mixed.Case@Example.COM", "password": "%s"}
                """.formatted(PASSWORD))
                .andExpect(status().isCreated());

        assertThat(accounts.findAll().getFirst().getEmail()).isEqualTo("mixed.case@example.com");
    }

    @Test
    void ignoresMerchantFieldsInRequestBody() throws Exception {
        register("""
                {
                  "email": "sneaky@example.com",
                  "password": "%s",
                  "merchantId": 99,
                  "merchantActive": true,
                  "buyerActive": false,
                  "capabilities": ["BUYER", "MERCHANT"]
                }
                """.formatted(PASSWORD))
                .andExpect(status().isCreated());

        Account account = accounts.findAll().getFirst();
        assertThat(account.getMerchantId()).isNull();
        assertThat(account.isMerchantActive()).isFalse();
        assertThat(account.isBuyerActive()).isTrue();
    }

    @Test
    void rejectsDuplicateEmailCaseInsensitivelyWith409() throws Exception {
        register("""
                {"email": "taken@example.com", "password": "%s"}
                """.formatted(PASSWORD))
                .andExpect(status().isCreated());

        register("""
                {"email": "TAKEN@Example.com", "password": "%s"}
                """.formatted(PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(accounts.count()).isEqualTo(1);
    }

    @Test
    void rejectsEmailAlreadyUsedByAdminAccountWith409() throws Exception {
        // Seeded by V2__seed_initial_admin.sql.
        register("""
                {"email": "admin-placeholder@basketry.example", "password": "%s"}
                """.formatted(PASSWORD))
                .andExpect(status().isConflict());

        assertThat(accounts.count()).isZero();
    }

    @Test
    void rejectsPasswordShorterThan8With400() throws Exception {
        register("""
                {"email": "buyer@example.com", "password": "1234567"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(accounts.count()).isZero();
    }

    @Test
    void acceptsPasswordOfExactly8() throws Exception {
        register("""
                {"email": "buyer@example.com", "password": "12345678"}
                """)
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsPasswordLongerThan72BytesWith400() throws Exception {
        register("""
                {"email": "buyer@example.com", "password": "%s"}
                """.formatted("a".repeat(73)))
                .andExpect(status().isBadRequest());

        assertThat(accounts.count()).isZero();
    }

    @Test
    void rejectsMissingOrMalformedEmailWith400() throws Exception {
        register("""
                {"password": "%s"}
                """.formatted(PASSWORD))
                .andExpect(status().isBadRequest());
        register("""
                {"email": "not-an-email", "password": "%s"}
                """.formatted(PASSWORD))
                .andExpect(status().isBadRequest());

        assertThat(accounts.count()).isZero();
    }

    @Test
    void neverLogsOrReturnsPlaintextPassword(CapturedOutput output) throws Exception {
        String shortPassword = "Secr3t!";
        String body = register("""
                {"email": "buyer@example.com", "password": "%s"}
                """.formatted(shortPassword))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        register("""
                {"email": "buyer@example.com", "password": "%s"}
                """.formatted(PASSWORD))
                .andExpect(status().isCreated());

        assertThat(body).doesNotContain(shortPassword);
        assertThat(output.getAll()).doesNotContain(shortPassword).doesNotContain(PASSWORD);
    }

    @Test
    void otherEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/accounts/1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/accounts/1/anything"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerIsPostOnly() throws Exception {
        mockMvc.perform(get("/accounts/register"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
    }

    private ResultActions register(String json) throws Exception {
        return mockMvc.perform(post("/accounts/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }
}
