package com.basketry.identity.merchantlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.identity.account.Account;
import com.basketry.identity.account.AccountRegistrationService;
import com.basketry.identity.account.AccountRepository;
import com.basketry.identity.config.InternalApiKey;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * POST /internal/accounts/merchant-link (DESIGN.md Section 3): API-key only,
 * upsert by email, and the fixed order of checks.
 */
@SpringBootTest(properties = "basketry.internal.api-key=" + MerchantLinkTests.KEY)
@AutoConfigureMockMvc
class MerchantLinkTests {

    static final String KEY = "test-only-internal-key-not-a-secret-000";

    private static final String URL = "/internal/accounts/merchant-link";
    private static final String PASSWORD = "Corr3ct-Horse!";
    private static final String INITIAL_PASSWORD = "Init1al-Pass!";

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

    @BeforeEach
    void clearAccounts() {
        accounts.deleteAll();
    }

    // --- 401: the API key is the only way in --------------------------------

    @Test
    void noKeyGets401() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(newMerchantBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        assertThat(accounts.count()).isZero();
    }

    @Test
    void wrongKeyGets401() throws Exception {
        for (String wrong : List.of("wrong", KEY + "x", KEY.substring(0, KEY.length() - 1), KEY.toUpperCase(), "")) {
            mockMvc.perform(withKey(wrong).content(newMerchantBody()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(""));
        }
        assertThat(accounts.count()).isZero();
    }

    @Test
    void buyerJwtWithoutKeyGets401() throws Exception {
        registrationService.registerBuyer("buyer@example.com", PASSWORD);
        String buyerToken = token("buyer@example.com", PASSWORD);

        mockMvc.perform(post(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newMerchantBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        assertThat(accounts.count()).isOne();
    }

    @Test
    void adminJwtWithoutKeyGets401() throws Exception {
        mockMvc.perform(post(URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(ADMIN_EMAIL, ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newMerchantBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        assertThat(accounts.count()).isZero();
    }

    @Test
    void adminJwtWithWrongKeyGets401() throws Exception {
        mockMvc.perform(withKey("wrong")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(ADMIN_EMAIL, ADMIN_PASSWORD))
                        .content(newMerchantBody()))
                .andExpect(status().isUnauthorized());
        assertThat(accounts.count()).isZero();
    }

    @Test
    void authorizationHeaderIsIgnoredWhenTheKeyIsValid() throws Exception {
        // Proves the JWT path is not wired here at all: a garbage bearer token is not even parsed.
        mockMvc.perform(withKey(KEY)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                        .content(newMerchantBody()))
                .andExpect(status().isCreated());
    }

    @Test
    void browserPreflightGetsNoCorsHeaders() throws Exception {
        mockMvc.perform(options(URL)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, InternalApiKey.HEADER))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    // --- 201 / 200 ---------------------------------------------------------

    @Test
    void newEmailCreatesLinkedAccountWith201() throws Exception {
        link(body("Merchant@Example.COM", 7L, INITIAL_PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("CREATED"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value("merchant@example.com"))
                .andExpect(jsonPath("$.buyerActive").value(true))
                .andExpect(jsonPath("$.merchantId").value(7))
                .andExpect(jsonPath("$.merchantActive").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.initialPassword").doesNotExist());

        // The new account logs in with the initial password and gets MERCHANT.
        String body = login("merchant@example.com", INITIAL_PASSWORD)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(claims(body), "$.capabilities")).containsExactly("BUYER", "MERCHANT");
    }

    @Test
    void existingAccountIsLinkedWith200AndKeepsItsPassword() throws Exception {
        Account existing = registrationService.registerBuyer("shop@example.com", PASSWORD);

        link(body("SHOP@example.com", 7L, INITIAL_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("LINKED"))
                .andExpect(jsonPath("$.id").value(existing.getId()))
                .andExpect(jsonPath("$.merchantId").value(7))
                .andExpect(jsonPath("$.merchantActive").value(true))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        login("shop@example.com", PASSWORD).andExpect(status().isOk());
        login("shop@example.com", INITIAL_PASSWORD).andExpect(status().isUnauthorized());
        assertThat(accounts.count()).isOne();
    }

    @Test
    void existingAccountIsLinkedWithoutAnyInitialPassword() throws Exception {
        registrationService.registerBuyer("shop@example.com", PASSWORD);

        link("""
                {"email": "shop@example.com", "merchantId": 7}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("LINKED"));
    }

    @Test
    void existingAccountIgnoresWeakInitialPassword() throws Exception {
        registrationService.registerBuyer("shop@example.com", PASSWORD);

        link(body("shop@example.com", 7L, "weak"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("LINKED"));
        login("shop@example.com", PASSWORD).andExpect(status().isOk());
    }

    // --- repeat calls ------------------------------------------------------

    @Test
    void repeatOfCreatingCallIs200AlreadyLinkedWithNoChange() throws Exception {
        String first = link(body("merchant@example.com", 7L, INITIAL_PASSWORD))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String passwordHashBefore = passwordHash("merchant@example.com");

        String second = link(body("MERCHANT@example.com", 7L, "Other-Passw0rd!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_LINKED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(withoutOutcome(second)).isEqualTo(withoutOutcome(first));
        assertThat(passwordHash("merchant@example.com")).isEqualTo(passwordHashBefore);
        assertThat(accounts.count()).isOne();
    }

    @Test
    void repeatDoesNotResetMerchantActiveChangedByTheEvent() throws Exception {
        link(body("merchant@example.com", 7L, INITIAL_PASSWORD)).andExpect(status().isCreated());
        // merchant.status.changed (merchant deactivated) arrived since the first call.
        jdbc.update("UPDATE account SET merchant_active = FALSE WHERE email = ?", "merchant@example.com");

        link(body("merchant@example.com", 7L, INITIAL_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_LINKED"))
                .andExpect(jsonPath("$.merchantActive").value(false));
        assertThat(merchantActive("merchant@example.com")).isFalse();
    }

    @Test
    void repeatAfterBuyerWasDeactivatedIsStill200AlreadyLinked() throws Exception {
        link(body("merchant@example.com", 7L, INITIAL_PASSWORD)).andExpect(status().isCreated());
        jdbc.update("UPDATE account SET buyer_active = FALSE WHERE email = ?", "merchant@example.com");

        link(body("merchant@example.com", 7L, INITIAL_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_LINKED"))
                .andExpect(jsonPath("$.buyerActive").value(false))
                .andExpect(jsonPath("$.merchantActive").value(true));
    }

    @Test
    void repeatOfLinkingCallIs200AlreadyLinked() throws Exception {
        registrationService.registerBuyer("shop@example.com", PASSWORD);
        link(body("shop@example.com", 7L, null)).andExpect(jsonPath("$.outcome").value("LINKED"));

        link(body("shop@example.com", 7L, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_LINKED"));
    }

    // --- 409, one test per code ----------------------------------------------

    @Test
    void adminEmailIs409EmailBelongsToAdmin() throws Exception {
        link(body("Admin-Placeholder@Basketry.example", 7L, INITIAL_PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EMAIL_BELONGS_TO_ADMIN"));
        assertThat(accounts.count()).isZero();
    }

    @Test
    void merchantHeldByAnotherAccountIs409ForANewEmail() throws Exception {
        link(body("first@example.com", 7L, INITIAL_PASSWORD)).andExpect(status().isCreated());

        link(body("second@example.com", 7L, INITIAL_PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MERCHANT_ALREADY_LINKED_TO_OTHER_ACCOUNT"));
        assertThat(accounts.findByEmail("second@example.com")).isEmpty();
    }

    @Test
    void merchantHeldByAnotherAccountIs409ForAnExistingAccount() throws Exception {
        link(body("first@example.com", 7L, INITIAL_PASSWORD)).andExpect(status().isCreated());
        registrationService.registerBuyer("second@example.com", PASSWORD);

        link(body("second@example.com", 7L, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MERCHANT_ALREADY_LINKED_TO_OTHER_ACCOUNT"));
        assertThat(accounts.findByEmail("second@example.com").orElseThrow().getMerchantId()).isNull();
    }

    @Test
    void accountLinkedToAnotherMerchantIs409() throws Exception {
        link(body("shop@example.com", 7L, INITIAL_PASSWORD)).andExpect(status().isCreated());

        link(body("shop@example.com", 8L, INITIAL_PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LINKED_TO_OTHER_MERCHANT"));
        assertThat(accounts.findByEmail("shop@example.com").orElseThrow().getMerchantId()).isEqualTo(7L);
    }

    @Test
    void deactivatedBuyerIs409AndIsNotLinked() throws Exception {
        Account account = registrationService.registerBuyer("shop@example.com", PASSWORD);
        jdbc.update("UPDATE account SET buyer_active = FALSE WHERE id = ?", account.getId());

        link(body("shop@example.com", 7L, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUYER_DEACTIVATED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Reactivate")));
        Account after = accounts.findById(account.getId()).orElseThrow();
        assertThat(after.getMerchantId()).isNull();
        assertThat(after.isMerchantActive()).isFalse();
    }

    // --- order of checks -----------------------------------------------------

    @Test
    void adminEmailIsCheckedBeforeMerchantConflict() throws Exception {
        link(body("first@example.com", 7L, INITIAL_PASSWORD)).andExpect(status().isCreated());

        link(body(ADMIN_EMAIL, 7L, INITIAL_PASSWORD))
                .andExpect(jsonPath("$.code").value("EMAIL_BELONGS_TO_ADMIN"));
    }

    @Test
    void merchantConflictIsCheckedBeforeOwnAccountState() throws Exception {
        link(body("first@example.com", 7L, INITIAL_PASSWORD)).andExpect(status().isCreated());
        link(body("second@example.com", 8L, INITIAL_PASSWORD)).andExpect(status().isCreated());
        jdbc.update("UPDATE account SET buyer_active = FALSE WHERE email = ?", "second@example.com");

        // second is both linked to merchant 8 and deactivated; merchant 7 belongs to first.
        link(body("second@example.com", 7L, null))
                .andExpect(jsonPath("$.code").value("MERCHANT_ALREADY_LINKED_TO_OTHER_ACCOUNT"));
    }

    @Test
    void otherMerchantIsCheckedBeforeDeactivated() throws Exception {
        link(body("shop@example.com", 7L, INITIAL_PASSWORD)).andExpect(status().isCreated());
        jdbc.update("UPDATE account SET buyer_active = FALSE WHERE email = ?", "shop@example.com");

        link(body("shop@example.com", 8L, null))
                .andExpect(jsonPath("$.code").value("ACCOUNT_LINKED_TO_OTHER_MERCHANT"));
    }

    // --- 400 ---------------------------------------------------------------

    @Test
    void invalidEmailIs400() throws Exception {
        for (String body : List.of(
                "{\"merchantId\": 7, \"initialPassword\": \"" + INITIAL_PASSWORD + "\"}",
                body("", 7L, INITIAL_PASSWORD),
                body("not-an-email", 7L, INITIAL_PASSWORD),
                body("a".repeat(250) + "@example.com", 7L, INITIAL_PASSWORD))) {
            link(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        assertThat(accounts.count()).isZero();
    }

    @Test
    void invalidMerchantIdIs400() throws Exception {
        for (String merchantId : List.of("null", "0", "-1", "\"seven\"", "1.5")) {
            link("""
                    {"email": "merchant@example.com", "merchantId": %s, "initialPassword": "%s"}
                    """.formatted(merchantId, INITIAL_PASSWORD))
                    .andExpect(status().isBadRequest());
        }
        link("""
                {"email": "merchant@example.com", "initialPassword": "%s"}
                """.formatted(INITIAL_PASSWORD))
                .andExpect(status().isBadRequest());
        assertThat(accounts.count()).isZero();
    }

    @Test
    void newAccountWithoutInitialPasswordIs400() throws Exception {
        link(body("merchant@example.com", 7L, null))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("initialPassword")));
        assertThat(accounts.count()).isZero();
    }

    @Test
    void newAccountWithWeakInitialPasswordIs400NamingEveryRule() throws Exception {
        link(body("merchant@example.com", 7L, "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.length()").value(4));
        assertThat(accounts.count()).isZero();
    }

    @Test
    void malformedOrMissingBodyIs400() throws Exception {
        link("not json").andExpect(status().isBadRequest());
        mockMvc.perform(withKey(KEY)).andExpect(status().isBadRequest());
        assertThat(accounts.count()).isZero();
    }

    // --- V3 unique index ---------------------------------------------------

    @Test
    void databaseRejectsTwoAccountsWithTheSameMerchantId() {
        insertRaw("one@example.com", 7L);

        assertThatThrownBy(() -> insertRaw("two@example.com", 7L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseAllowsManyAccountsWithoutAMerchant() {
        for (int i = 0; i < 5; i++) {
            insertRaw("buyer" + i + "@example.com", null);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE merchant_id IS NULL", Long.class))
                .isEqualTo(5L);
    }

    // --- helpers -----------------------------------------------------------

    private MockHttpServletRequestBuilder withKey(String key) {
        return post(URL).header(InternalApiKey.HEADER, key).contentType(MediaType.APPLICATION_JSON);
    }

    private ResultActions link(String body) throws Exception {
        return mockMvc.perform(withKey(KEY).content(body));
    }

    private static String newMerchantBody() {
        return body("merchant@example.com", 7L, INITIAL_PASSWORD);
    }

    private static String body(String email, Long merchantId, String initialPassword) {
        return """
                {"email": "%s", "merchantId": %s, "initialPassword": %s}
                """.formatted(email, merchantId, initialPassword == null ? "null" : "\"" + initialPassword + "\"");
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, password)));
    }

    private String token(String email, String password) throws Exception {
        String body = login(email, password).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    /** Decodes the JWT payload of a login response (signature checks live in LoginTests). */
    private static String claims(String loginBody) {
        String token = JsonPath.read(loginBody, "$.accessToken");
        return new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private static Map<String, Object> withoutOutcome(String json) {
        Map<String, Object> map = new java.util.HashMap<>(JsonPath.read(json, "$"));
        map.remove("outcome");
        return map;
    }

    private String passwordHash(String email) {
        return jdbc.queryForObject("SELECT password_hash FROM account WHERE email = ?", String.class, email);
    }

    private boolean merchantActive(String email) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT merchant_active FROM account WHERE email = ?", Boolean.class, email));
    }

    private void insertRaw(String email, Long merchantId) {
        jdbc.update("INSERT INTO account (email, password_hash, buyer_active, merchant_id, merchant_active, created_at) "
                        + "VALUES (?, 'x', TRUE, ?, ?, ?)",
                email, merchantId, merchantId != null, java.sql.Timestamp.from(Instant.now()));
    }
}
