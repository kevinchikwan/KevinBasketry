package com.basketry.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

    private static final String PASSWORD = "Corr3ct-Horse!";

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

    // --- password policy (REQUIREMENTS.md Section 4) --------------------------
    // Each rejected case fails exactly one rule, and the response must name that rule.

    @Test
    void acceptsPasswordMeetingAllRules() throws Exception {
        register("""
                {"email": "buyer@example.com", "password": "Abcdef1!"}
                """)
                .andExpect(status().isCreated());
    }

    @Test
    void acceptsPasswordsAtBothLengthLimits() throws Exception {
        registerWithPassword("min@example.com", "Abcde1!x").andExpect(status().isCreated());     // 8
        registerWithPassword("max@example.com", "Abcde1!xyzabcde").andExpect(status().isCreated()); // 15
    }

    @Test
    void rejectsTooShortPassword() throws Exception {
        assertRejectedFor("Abcd1!x", "Password must be at least 8 characters.");             // 7
    }

    @Test
    void rejectsTooLongPassword() throws Exception {
        assertRejectedFor("Abcde1!xyzabcdef", "Password must be at most 15 characters.");    // 16
    }

    @Test
    void rejectsPasswordWithoutUppercase() throws Exception {
        assertRejectedFor("abcdef1!", "Password must contain an uppercase letter (A-Z).");
    }

    @Test
    void rejectsPasswordWithoutLowercase() throws Exception {
        assertRejectedFor("ABCDEF1!", "Password must contain a lowercase letter (a-z).");
    }

    @Test
    void rejectsPasswordWithoutDigit() throws Exception {
        assertRejectedFor("Abcdefg!", "Password must contain a digit (0-9).");
    }

    @Test
    void rejectsPasswordWithoutSpecialCharacter() throws Exception {
        assertRejectedFor("Abcdefg1", "Password must contain a special character from !@#$%^&*()-_=+.");
    }

    @Test
    void specialCharacterMustComeFromTheAllowedList() throws Exception {
        // '?' and '.' are allowed in a password but don't count as the special character.
        assertRejectedFor("Abcdef1?.", "Password must contain a special character from !@#$%^&*()-_=+.");
    }

    @Test
    void namesEveryFailedRuleAtOnce() throws Exception {
        registerWithPassword("buyer@example.com", "abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations", contains(
                        "Password must be at least 8 characters.",
                        "Password must contain an uppercase letter (A-Z).",
                        "Password must contain a digit (0-9).",
                        "Password must contain a special character from !@#$%^&*()-_=+.")));

        assertThat(accounts.count()).isZero();
    }

    @Test
    void lengthCountsCharactersNotUtf16Units() throws Exception {
        // 15 code points; the emoji is 2 UTF-16 units, so String.length() would say 16.
        registerWithPassword("buyer@example.com", "Abcde1!xyzabcd😀").andExpect(status().isCreated());
    }

    @Test
    void sixteenCharactersIncludingEmojiIsTooLong() throws Exception {
        assertRejectedFor("Abcde1!xyzabcde😀", "Password must be at most 15 characters.");
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

    /** Expects a 400 problem whose detail and violations list name exactly the one failed rule. */
    private void assertRejectedFor(String password, String expectedViolation) throws Exception {
        registerWithPassword("buyer@example.com", password)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(expectedViolation))
                .andExpect(jsonPath("$.violations", contains(expectedViolation)));

        assertThat(accounts.count()).isZero();
    }

    /** Builds the JSON with the JSON-escaped password, so any character can be tested. */
    private ResultActions registerWithPassword(String email, String password) throws Exception {
        return register("""
                {"email": "%s", "password": "%s"}
                """.formatted(email, password.replace("\\", "\\\\").replace("\"", "\\\"")));
    }

    private ResultActions register(String json) throws Exception {
        return mockMvc.perform(post("/accounts/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }
}
