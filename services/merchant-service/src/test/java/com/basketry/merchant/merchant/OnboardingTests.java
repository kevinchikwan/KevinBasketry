package com.basketry.merchant.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.merchant.support.MerchantServiceTestBase;
import com.basketry.merchant.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.servlet.ResultActions;

/**
 * POST /merchants (FR-A1): the merchant is saved PENDING, then linked through
 * identity-service's merchant-link. Always 201 once saved; a failed link
 * leaves it PENDING with {@code accountLinkError}.
 */
class OnboardingTests extends MerchantServiceTestBase {

    private static final String INITIAL_PASSWORD = "Init1al-Pass!";

    private static Map<String, Object> validBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "  Kevin's Kitchen  ");
        body.put("category", " Food ");
        body.put("kycVerified", true);
        body.put("commissionRate", new BigDecimal("5.00"));
        body.put("loginEmail", "  Kevin@Example.COM ");
        body.put("initialPassword", INITIAL_PASSWORD);
        return body;
    }

    private ResultActions onboard(String token, Map<String, Object> body) throws Exception {
        return mockMvc.perform(bearer(post("/merchants"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    private Merchant onlyMerchant() {
        List<Merchant> all = merchants.findAll();
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    // --- success ----------------------------------------------------------

    @Test
    void newAccountIsCreatedAndMerchantLinked() throws Exception {
        identityLinks(HttpStatus.CREATED, "CREATED");

        ResultActions result = onboard(TestTokens.admin(), validBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Kevin's Kitchen"))
                .andExpect(jsonPath("$.category").value("Food"))
                .andExpect(jsonPath("$.kycVerified").value(true))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.commissionRate").value(5.0))
                .andExpect(jsonPath("$.loginEmail").value("kevin@example.com"))
                .andExpect(jsonPath("$.accountLinkStatus").value("LINKED"))
                .andExpect(jsonPath("$.accountOutcome").value("CREATED"))
                .andExpect(jsonPath("$.accountLinkError").isEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$..initialPassword").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"commissionRate\":5.00")));

        Merchant saved = onlyMerchant();
        assertThat(saved.getAccountLinkStatus()).isEqualTo(AccountLinkStatus.LINKED);
        assertThat(saved.getMerchantCode()).isEqualTo(String.format("MCH-%03d", saved.getId()));
        result.andExpect(jsonPath("$.id").value(saved.getId()))
                .andExpect(jsonPath("$.merchantCode").value(saved.getMerchantCode()))
                .andExpect(header().string("Location", "http://localhost/merchants/" + saved.getMerchantCode()));

        // The link call: normalized email, this merchant's id, the initial password.
        assertThat(linkRequests).hasSize(1);
        Map<String, Object> sent = sentLink(0);
        assertThat(sent.get("email")).isEqualTo("kevin@example.com");
        assertThat(((Number) sent.get("merchantId")).longValue()).isEqualTo(saved.getId());
        assertThat(sent.get("initialPassword")).isEqualTo(INITIAL_PASSWORD);
    }

    @Test
    void existingAccountIsLinkedAndNoPasswordIsSentWhenNoneGiven() throws Exception {
        identityLinks(HttpStatus.OK, "LINKED");
        Map<String, Object> body = validBody();
        body.remove("initialPassword");

        onboard(TestTokens.admin(), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountLinkStatus").value("LINKED"))
                .andExpect(jsonPath("$.accountOutcome").value("LINKED"));

        Map<String, Object> sent = sentLink(0);
        assertThat(sent).doesNotContainKey("initialPassword");
    }

    @Test
    void merchantCodesFollowTheSequence() throws Exception {
        identityLinks(HttpStatus.CREATED, "CREATED");
        identityLinks(HttpStatus.CREATED, "CREATED");
        Map<String, Object> second = validBody();
        second.put("loginEmail", "second@example.com");

        String first = JsonPath.read(onboard(TestTokens.admin(), validBody())
                .andReturn().getResponse().getContentAsString(), "$.merchantCode");
        String next = JsonPath.read(onboard(TestTokens.admin(), second)
                .andReturn().getResponse().getContentAsString(), "$.merchantCode");

        assertThat(first).matches("MCH-\\d{3,}");
        assertThat(Long.parseLong(next.substring(4))).isEqualTo(Long.parseLong(first.substring(4)) + 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "100", "100.00", "12.5"})
    void commissionRateBoundariesAreAccepted(String rate) throws Exception {
        identityLinks(HttpStatus.CREATED, "CREATED");
        Map<String, Object> body = validBody();
        body.put("commissionRate", new BigDecimal(rate));

        onboard(TestTokens.admin(), body).andExpect(status().isCreated());

        assertThat(onlyMerchant().getCommissionRate()).isEqualByComparingTo(rate);
    }

    @Test
    void activeInTheBodyIs400NamingItBecauseANewMerchantAlwaysStartsActive() throws Exception {
        Map<String, Object> body = validBody();
        body.put("active", false);

        onboard(TestTokens.admin(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("Unknown field(s): active. A new merchant always starts active."));
        // Same rule for a non-admin: validation comes before the admin check.
        onboard(TestTokens.buyer(5), body).andExpect(status().isBadRequest());

        assertThat(merchants.count()).isZero();
    }

    @Test
    void everyUnknownFieldIsNamedInAlphabeticalOrderAndNothingIsSaved() throws Exception {
        Map<String, Object> body = validBody();
        body.put("nickname", "kk");
        body.put("merchantCode", "MCH-777");

        onboard(TestTokens.admin(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown field(s): merchantCode, nickname."));

        assertThat(merchants.count()).isZero();
    }

    @Test
    void timedOutFirstAttemptThatCreatedTheAccountIsAlreadyLinkedOnTheRetry() throws Exception {
        // The first attempt created the account in identity-service, but its answer never arrived.
        expectLink(ExpectedCount.once()).andRespond(withException(new SocketTimeoutException("read timed out")));
        identityLinks(HttpStatus.OK, "ALREADY_LINKED");

        onboard(TestTokens.admin(), validBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountLinkStatus").value("LINKED"))
                .andExpect(jsonPath("$.accountOutcome").value("ALREADY_LINKED"))
                .andExpect(jsonPath("$.accountLinkError").isEmpty());

        assertThat(onlyMerchant().getAccountLinkStatus()).isEqualTo(AccountLinkStatus.LINKED);
    }

    // --- link failures: saved PENDING, 201, accountLinkError --------------

    static Stream<Arguments> identityConflicts() {
        return Stream.of(
                Arguments.of("EMAIL_BELONGS_TO_ADMIN", "This email is an admin login. Use a different email."),
                Arguments.of("ACCOUNT_LINKED_TO_OTHER_MERCHANT",
                        "This login already runs another merchant. Use a different email."),
                Arguments.of("BUYER_DEACTIVATED",
                        "This buyer account is deactivated. Reactivate it first (Accounts page), then retry."),
                Arguments.of("MERCHANT_ALREADY_LINKED_TO_OTHER_ACCOUNT",
                        "An earlier attempt already linked this merchant to a different login. "
                                + "Retry with the original email."));
    }

    @ParameterizedTest
    @MethodSource("identityConflicts")
    void identityConflictLeavesMerchantPendingWithItsCode(String code, String adminMessage) throws Exception {
        identityRefuses(HttpStatus.CONFLICT, conflict(code)); // exactly once: a 409 is never retried

        onboard(TestTokens.admin(), validBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountLinkStatus").value("PENDING"))
                .andExpect(jsonPath("$.accountOutcome").isEmpty())
                .andExpect(jsonPath("$.accountLinkError.code").value(code))
                .andExpect(jsonPath("$.accountLinkError.detail").value(adminMessage))
                .andExpect(jsonPath("$.accountLinkError.violations").isEmpty());

        assertThat(onlyMerchant().getAccountLinkStatus()).isEqualTo(AccountLinkStatus.PENDING);
    }

    @Test
    void unknownConflictCodeIsLinkRejected() throws Exception {
        identityRefuses(HttpStatus.CONFLICT, conflict("SOMETHING_NEW"));

        onboard(TestTokens.admin(), validBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountLinkStatus").value("PENDING"))
                .andExpect(jsonPath("$.accountLinkError.code").value("LINK_REJECTED"));
    }

    @Test
    void weakPasswordIsPassedThroughWithEveryViolation() throws Exception {
        identityRefuses(HttpStatus.BAD_REQUEST, """
                {"title":"Password does not meet the policy","status":400,
                 "detail":"Must contain a digit. Must contain a special character.",
                 "violations":["Must contain a digit.","Must contain a special character."]}""");

        onboard(TestTokens.admin(), validBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountLinkStatus").value("PENDING"))
                .andExpect(jsonPath("$.accountLinkError.code").value("WEAK_PASSWORD"))
                .andExpect(jsonPath("$.accountLinkError.detail")
                        .value("Must contain a digit. Must contain a special character."))
                .andExpect(jsonPath("$.accountLinkError.violations[0]").value("Must contain a digit."))
                .andExpect(jsonPath("$.accountLinkError.violations[1]").value("Must contain a special character."));
    }

    @Test
    void missingPasswordForANewAccountIsInitialPasswordRequired() throws Exception {
        identityRefuses(HttpStatus.BAD_REQUEST,
                "{\"status\":400,\"detail\":\"initialPassword is required when no account exists for this email.\"}");
        Map<String, Object> body = validBody();
        body.remove("initialPassword");

        onboard(TestTokens.admin(), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountLinkStatus").value("PENDING"))
                .andExpect(jsonPath("$.accountLinkError.code").value("INITIAL_PASSWORD_REQUIRED"));
    }

    @Test
    void serverErrorTwiceIsIdentityUnavailableAfterOneRetry() throws Exception {
        expectLink(ExpectedCount.times(2)).andRespond(withServerError());

        onboard(TestTokens.admin(), validBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountLinkStatus").value("PENDING"))
                .andExpect(jsonPath("$.accountLinkError.code").value("IDENTITY_UNAVAILABLE"))
                .andExpect(jsonPath("$.accountLinkError.detail").value("Merchant saved; login not linked yet. Retry."));

        assertThat(onlyMerchant().getAccountLinkStatus()).isEqualTo(AccountLinkStatus.PENDING);
    }

    @Test
    void serverErrorThenSuccessIsLinkedByTheAutomaticRetry() throws Exception {
        expectLink(ExpectedCount.once()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        identityLinks(HttpStatus.CREATED, "CREATED");

        onboard(TestTokens.admin(), validBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountLinkStatus").value("LINKED"))
                .andExpect(jsonPath("$.accountOutcome").value("CREATED"));

        // Both attempts were the same call: same email, same merchantId.
        assertThat(linkRequests).hasSize(2);
        assertThat(linkRequests.get(1)).isEqualTo(linkRequests.get(0));
    }

    @Test
    void timeoutTwiceIsIdentityUnavailable() throws Exception {
        expectLink(ExpectedCount.times(2)).andRespond(withException(new SocketTimeoutException("read timed out")));

        onboard(TestTokens.admin(), validBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountLinkStatus").value("PENDING"))
                .andExpect(jsonPath("$.accountLinkError.code").value("IDENTITY_UNAVAILABLE"));
    }

    @Test
    void unauthorizedFromIdentityIsAuthFailedAndNotRetried() throws Exception {
        expectLink(ExpectedCount.once()).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        onboard(TestTokens.admin(), validBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountLinkStatus").value("PENDING"))
                .andExpect(jsonPath("$.accountLinkError.code").value("IDENTITY_AUTH_FAILED"));
    }

    @Test
    void anySuccessStatusCountsAsLinkedEvenWithoutAKnownOutcome() throws Exception {
        expectLink(ExpectedCount.once()).andRespond(withStatus(HttpStatus.OK));

        onboard(TestTokens.admin(), validBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountLinkStatus").value("LINKED"))
                .andExpect(jsonPath("$.accountOutcome").isEmpty())
                .andExpect(jsonPath("$.accountLinkError").isEmpty());
    }

    // --- duplicates --------------------------------------------------------

    @Test
    void loginEmailAlreadyUsedByAnotherMerchantIs409WithItsCode() throws Exception {
        Merchant existing = savePending("kevin@example.com");

        onboard(TestTokens.admin(), validBody()) // "  Kevin@Example.COM " normalizes to the same email
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOGIN_EMAIL_IN_USE"))
                .andExpect(jsonPath("$.merchantCode").value(existing.getMerchantCode()));

        assertThat(merchants.count()).isEqualTo(1);
    }

    // --- validation (before the admin check) ------------------------------

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                invalid("missing name", body -> body.remove("name")),
                invalid("blank name", body -> body.put("name", "   ")),
                invalid("name over 100", body -> body.put("name", "x".repeat(101))),
                invalid("missing category", body -> body.remove("category")),
                invalid("category over 50", body -> body.put("category", "x".repeat(51))),
                invalid("missing kycVerified", body -> body.remove("kycVerified")),
                invalid("null kycVerified", body -> body.put("kycVerified", null)),
                invalid("string kycVerified", body -> body.put("kycVerified", "false")),
                invalid("number kycVerified", body -> body.put("kycVerified", 1)),
                invalid("missing commissionRate", body -> body.remove("commissionRate")),
                invalid("negative commissionRate", body -> body.put("commissionRate", new BigDecimal("-0.01"))),
                invalid("commissionRate over 100", body -> body.put("commissionRate", new BigDecimal("100.01"))),
                invalid("three decimals", body -> body.put("commissionRate", new BigDecimal("5.005"))),
                invalid("commissionRate as a string", body -> body.put("commissionRate", "5.00")),
                invalid("missing loginEmail", body -> body.remove("loginEmail")),
                invalid("invalid loginEmail", body -> body.put("loginEmail", "not-an-email")));
    }

    private static Arguments invalid(String name, Consumer<Map<String, Object>> change) {
        return Arguments.of(name, change);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBodies")
    void invalidBodyIs400AndNothingIsSaved(String name, Consumer<Map<String, Object>> change) throws Exception {
        Map<String, Object> body = validBody();
        change.accept(body);

        onboard(TestTokens.admin(), body).andExpect(status().isBadRequest());
        // Validation runs before the admin check, so a non-admin gets 400 too.
        onboard(TestTokens.buyer(5), body).andExpect(status().isBadRequest());

        assertThat(merchants.count()).isZero();
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mockMvc.perform(bearer(post("/merchants"), TestTokens.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest());
    }

    // --- authorization -----------------------------------------------------

    @Test
    void nonAdminTokensGet403AndNothingIsSaved() throws Exception {
        onboard(TestTokens.buyer(5), validBody()).andExpect(status().isForbidden());
        onboard(TestTokens.merchant(5, 9), validBody()).andExpect(status().isForbidden());

        assertThat(merchants.count()).isZero();
    }

    @Test
    void noTokenGets401() throws Exception {
        mockMvc.perform(post("/merchants").contentType(MediaType.APPLICATION_JSON).content(json(validBody())))
                .andExpect(status().isUnauthorized());

        assertThat(merchants.count()).isZero();
    }

    // --- passwords stay out of logs ----------------------------------------

    @Test
    void toStringMasksTheInitialPassword() {
        CreateMerchantRequest request = new CreateMerchantRequest("Shop", "Food", true, BigDecimal.ONE,
                "a@example.com", INITIAL_PASSWORD);

        assertThat(request.toString()).doesNotContain(INITIAL_PASSWORD).contains("initialPassword=***");
        assertThat(request.unknownFields()).isEmpty();
    }
}
