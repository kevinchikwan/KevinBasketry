package com.basketry.identity.merchantstatus;

import static com.basketry.identity.merchantstatus.StatusEvents.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.identity.account.Account;
import com.basketry.identity.account.AccountRegistrationService;
import com.basketry.identity.account.AccountRepository;
import com.basketry.identity.merchantlink.MerchantLinkService;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What one merchant.status.changed value does (DESIGN.md Section 5), without
 * Kafka: the version rules, the strict parsing, and the effect on login.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MerchantStatusHandlerTests {

    private static final String PASSWORD = "Corr3ct-Horse!";
    private static final long MERCHANT = 42;

    @Autowired
    private MerchantStatusHandler handler;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountRegistrationService registrationService;

    @Autowired
    private MerchantLinkService linkService;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    private long accountId;

    @BeforeEach
    void linkAnAccount() {
        accounts.deleteAll();
        accountId = registrationService.registerBuyer("shop@example.com", PASSWORD).getId();
        linkService.link("shop@example.com", MERCHANT, null);
    }

    private Account account() {
        return accounts.findById(accountId).orElseThrow();
    }

    private void assertState(boolean merchantActive, long version) {
        assertThat(account().isMerchantActive()).as("merchantActive").isEqualTo(merchantActive);
        assertThat(account().getMerchantStatusVersion()).as("merchantStatusVersion").isEqualTo(version);
    }

    // --- the link ------------------------------------------------------------

    @Test
    void aNewLinkIsActiveAtVersionZero() {
        assertState(true, 0);
    }

    @Test
    void anAccountWithoutALinkIsAtVersionZero() {
        Account buyer = registrationService.registerBuyer("buyer@example.com", PASSWORD);

        assertThat(accounts.findById(buyer.getId()).orElseThrow().getMerchantStatusVersion()).isZero();
    }

    @Test
    void aRepeatedLinkChangesNeitherTheStatusNorTheVersion() {
        handler.handle(event(MERCHANT, false, 3));

        linkService.link("shop@example.com", MERCHANT, null);

        assertState(false, 3);
    }

    // --- version rules -------------------------------------------------------

    @Test
    void aNewerEventIsApplied() {
        handler.handle(event(MERCHANT, false, 1));
        assertState(false, 1);

        handler.handle(event(MERCHANT, true, 2));
        assertState(true, 2);
    }

    @Test
    void aGapInVersionsIsApplied() {
        handler.handle(event(MERCHANT, false, 3));

        assertState(false, 3);
    }

    @Test
    void anEventWithTheAppliedVersionIsIgnoredEvenIfItsValueDiffers() {
        handler.handle(event(MERCHANT, false, 2));

        handler.handle(event(MERCHANT, true, 2));

        assertState(false, 2);
    }

    @Test
    void anOlderEventIsIgnored() {
        handler.handle(event(MERCHANT, false, 3));

        handler.handle(event(MERCHANT, true, 2));

        assertState(false, 3);
    }

    @Test
    void aDuplicateChangesNothing() {
        String value = event(MERCHANT, false, 1);
        handler.handle(value);
        handler.handle(event(MERCHANT, true, 2));

        handler.handle(value);

        assertState(true, 2);
    }

    @Test
    void outOfOrderDeliveryEndsAtTheNewestEvent() {
        handler.handle(event(MERCHANT, true, 2));
        handler.handle(event(MERCHANT, false, 3));
        handler.handle(event(MERCHANT, false, 1));

        assertState(false, 3);
    }

    // --- what an event touches -----------------------------------------------

    @Test
    void onlyTheMerchantStatusChanges() {
        Account before = account();

        handler.handle(event(MERCHANT, false, 1));

        Account after = account();
        assertThat(after.isBuyerActive()).isEqualTo(before.isBuyerActive());
        assertThat(after.getMerchantId()).isEqualTo(MERCHANT);
        assertThat(after.getEmail()).isEqualTo(before.getEmail());
        assertThat(after.getPasswordHash()).isEqualTo(before.getPasswordHash());
    }

    @Test
    void onlyTheAccountOfThatMerchantChanges() {
        long other = registrationService.registerBuyer("other@example.com", PASSWORD).getId();
        linkService.link("other@example.com", MERCHANT + 1, null);

        handler.handle(event(MERCHANT, false, 1));

        Account untouched = accounts.findById(other).orElseThrow();
        assertThat(untouched.isMerchantActive()).isTrue();
        assertThat(untouched.getMerchantStatusVersion()).isZero();
    }

    @Test
    void aDeactivatedBuyerStillGetsTheEvent() {
        accounts.findById(accountId).ifPresent(account -> {
            account.setBuyerActive(false);
            accounts.save(account);
        });

        handler.handle(event(MERCHANT, false, 1));

        assertState(false, 1);
        assertThat(account().isBuyerActive()).isFalse();
    }

    @Test
    void anEventWithNoLinkedAccountIsSkippedWithoutFailing() {
        handler.handle(event(MERCHANT + 100, false, 1));

        assertState(true, 0);
    }

    @Test
    void fieldsThatAreNotUsedMayBeMissingOrNew() {
        handler.handle("""
                {"schemaVersion":1,"merchantId":%d,"active":false,"version":1,"somethingNew":{"a":[1]}}"""
                .formatted(MERCHANT));

        assertState(false, 1);
    }

    // --- values that can never be applied ------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "not json",
        "{\"schemaVersion\":1,",
        "null",
        "[]",
        "42",
        "{}",
        // Unknown or unusable schemaVersion, even with a good body.
        "{\"merchantId\":42,\"active\":false,\"version\":9}",
        "{\"schemaVersion\":2,\"merchantId\":42,\"active\":false,\"version\":9}",
        "{\"schemaVersion\":0,\"merchantId\":42,\"active\":false,\"version\":9}",
        "{\"schemaVersion\":\"1\",\"merchantId\":42,\"active\":false,\"version\":9}",
        "{\"schemaVersion\":1.0,\"merchantId\":42,\"active\":false,\"version\":9}",
        // Missing fields.
        "{\"schemaVersion\":1,\"active\":false,\"version\":9}",
        "{\"schemaVersion\":1,\"merchantId\":42,\"version\":9}",
        "{\"schemaVersion\":1,\"merchantId\":42,\"active\":false}",
        // Explicit nulls.
        "{\"schemaVersion\":1,\"merchantId\":null,\"active\":false,\"version\":9}",
        "{\"schemaVersion\":1,\"merchantId\":42,\"active\":null,\"version\":9}",
        "{\"schemaVersion\":1,\"merchantId\":42,\"active\":false,\"version\":null}",
        // The strict mapper: never converted.
        "{\"schemaVersion\":1,\"merchantId\":42,\"active\":\"false\",\"version\":9}",
        "{\"schemaVersion\":1,\"merchantId\":42,\"active\":\"true\",\"version\":9}",
        "{\"schemaVersion\":1,\"merchantId\":42,\"active\":1,\"version\":9}",
        "{\"schemaVersion\":1,\"merchantId\":42,\"active\":0,\"version\":9}",
        "{\"schemaVersion\":1,\"merchantId\":\"42\",\"active\":false,\"version\":9}",
        "{\"schemaVersion\":1,\"merchantId\":42,\"active\":false,\"version\":\"9\"}",
        "{\"schemaVersion\":1,\"merchantId\":42,\"active\":false,\"version\":9.5}",
        // Out of range.
        "{\"schemaVersion\":1,\"merchantId\":0,\"active\":false,\"version\":9}",
        "{\"schemaVersion\":1,\"merchantId\":-42,\"active\":false,\"version\":9}",
        "{\"schemaVersion\":1,\"merchantId\":42,\"active\":false,\"version\":0}",
        "{\"schemaVersion\":1,\"merchantId\":42,\"active\":false,\"version\":-1}",
        "{\"schemaVersion\":1,\"merchantId\":42,\"active\":false,\"version\":99999999999999999999}"})
    void aBadValueIsRefusedAndChangesNothing(String value) {
        assertThatThrownBy(() -> handler.handle(value)).isInstanceOf(InvalidMerchantStatusEventException.class);

        assertState(true, 0);
    }

    @Test
    void aRecordWithoutAValueIsRefused() {
        assertThatThrownBy(() -> handler.handle(null)).isInstanceOf(InvalidMerchantStatusEventException.class);
    }

    // --- login ---------------------------------------------------------------

    @Test
    void loginFollowsTheStatusAndAnIssuedTokenKeepsItsCapabilities() throws Exception {
        String firstToken = login();
        assertThat(capabilities(firstToken)).containsExactly("BUYER", "MERCHANT");
        assertThat(claims(firstToken)).contains("\"merchantId\":" + MERCHANT);

        handler.handle(event(MERCHANT, false, 1));

        String secondToken = login();
        assertThat(capabilities(secondToken)).containsExactly("BUYER");
        assertThat(claims(secondToken)).doesNotContain("merchantId");

        // Not revoked: valid until it expires (DESIGN.md Section 2, Tokens).
        assertThat(capabilities(firstToken)).contains("MERCHANT");
        mockMvc.perform(get("/accounts/{id}", accountId).header(HttpHeaders.AUTHORIZATION, "Bearer " + firstToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantActive").value(false));

        handler.handle(event(MERCHANT, true, 2));

        assertThat(capabilities(login())).containsExactly("BUYER", "MERCHANT");
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"shop@example.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static String claims(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    private static List<String> capabilities(String token) {
        return JsonPath.read(claims(token), "$.capabilities");
    }

    // --- the switch ----------------------------------------------------------

    @Test
    void theListenerIsOffUnlessATestTurnsItOn() {
        assertThat(context.getBeansOfType(MerchantStatusListener.class)).isEmpty();
    }
}
