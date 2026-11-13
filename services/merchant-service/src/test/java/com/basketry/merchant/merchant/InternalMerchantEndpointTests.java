package com.basketry.merchant.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.merchant.identityclient.InternalApiKey;
import com.basketry.merchant.support.MerchantServiceTestBase;
import com.basketry.merchant.support.TestTokens;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GET /internal/merchants and its /internal/** chain (DESIGN.md Section 3,
 * merchant-service), for order-service's checkout. Each test names the
 * mutation it catches.
 */
class InternalMerchantEndpointTests extends MerchantServiceTestBase {

    private static final String PATH = "/internal/merchants";

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService otherThreads;

    @BeforeEach
    void startThreads() {
        otherThreads = Executors.newCachedThreadPool();
    }

    @AfterEach
    void stopThreads() {
        otherThreads.shutdownNow();
    }

    private static MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder request) {
        return request.header(InternalApiKey.HEADER, API_KEY);
    }

    private static String ids(Object... ids) {
        StringBuilder joined = new StringBuilder();
        for (Object id : ids) {
            joined.append(joined.isEmpty() ? "" : ",").append(id);
        }
        return joined.toString();
    }

    // ---- the answer ----

    /**
     * Each merchant once, in id order, unknown ids left out, inactive and
     * PENDING merchants included, and only id, active and commissionRate.
     * Mutations: answer only active merchants (order-service could not tell a
     * deactivated merchant from an unknown one); a List instead of a set (a
     * repeated id answered twice); "order by m.id desc"; the full
     * MerchantResponse (loginEmail and merchantCode exposed).
     */
    @Test
    void eachMerchantOnceInIdOrderWithItsStatusAndRate() throws Exception {
        Merchant first = saveLinked("first@example.com");
        Merchant pending = savePending("pending@example.com");
        Merchant inactive = saveLinked("inactive@example.com");
        jdbc.update("UPDATE merchant SET active = FALSE, commission_rate = 12.50 WHERE id = ?", inactive.getId());

        mockMvc.perform(internal(get(PATH).param("ids",
                        ids(inactive.getId(), first.getId(), 999_999, first.getId(), pending.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].id").value(first.getId()))
                .andExpect(jsonPath("$.content[1].id").value(pending.getId()))
                .andExpect(jsonPath("$.content[2].id").value(inactive.getId()))
                .andExpect(jsonPath("$.content[0].active").value(true))
                .andExpect(jsonPath("$.content[1].active").value(true))
                .andExpect(jsonPath("$.content[2].active").value(false))
                .andExpect(jsonPath("$.content[2].commissionRate").value(12.50))
                .andExpect(jsonPath("$.content[0].loginEmail").doesNotExist())
                .andExpect(jsonPath("$.content[0].merchantCode").doesNotExist())
                .andExpect(jsonPath("$.content[0].length()").value(3));
    }

    /** Mutation: an unknown id answered as an empty or null item instead of left out. */
    @Test
    void onlyUnknownIdsGiveAnEmptyContent() throws Exception {
        mockMvc.perform(internal(get(PATH).param("ids", "999998,999999")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"content\":[]}", org.springframework.test.json.JsonCompareMode.STRICT));
    }

    /**
     * The rate keeps its scale in the JSON text: 5.00 and 12.50, not 5.0 or 5.
     * Checked on the raw body, because a JSON path compares numbers by value.
     * Mutations: the rate written as a double ({@code doubleValue()}, giving
     * 5.0); {@code stripTrailingZeros()} (giving 5 and 12.5).
     */
    @Test
    void theRateKeepsItsScale() throws Exception {
        Merchant five = saveLinked("five@example.com");
        Merchant twelve = saveLinked("twelve@example.com");
        jdbc.update("UPDATE merchant SET commission_rate = 12.50 WHERE id = ?", twelve.getId());

        mockMvc.perform(internal(get(PATH).param("ids", ids(five.getId(), twelve.getId()))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"commissionRate\":5.00")))
                .andExpect(content().string(containsString("\"commissionRate\":12.50")));
    }

    /**
     * A plain read: a PATCH holding the merchant's row lock (not yet committed)
     * does not make the read wait, and the read sees the committed rate.
     * Mutation: {@code @Lock(PESSIMISTIC_WRITE)} on the query (the read then
     * waits for the lock and the 2 s bound fails).
     */
    @Test
    void aHeldRowLockDoesNotBlockTheReadWhichSeesTheCommittedRate() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            Merchant locked = merchants.findByIdForUpdate(merchant.getId()).orElseThrow();
            locked.setCommissionRate(new BigDecimal("9.99"));
            merchants.flush();
            Future<MvcResult> read = otherThreads.submit(() -> mockMvc.perform(
                    internal(get(PATH).param("ids", Long.toString(merchant.getId())))).andReturn());
            try {
                MvcResult result = read.get(2, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                assertThat(result.getResponse().getContentAsString()).contains("\"commissionRate\":5.00");
            } catch (Exception waitedOrFailed) {
                throw new AssertionError("The read waited for the row lock or failed", waitedOrFailed);
            }
        });
    }

    // ---- the ids ----

    /**
     * Mutations: drop the null check (an empty element in {@code 1,,2} then
     * throws a NullPointerException: 500); {@code id < 0} instead of
     * {@code id <= 0} (0 accepted).
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-1", "1,0", "abc", "1,,2", "1.5"})
    void invalidIdsAre400(String ids) throws Exception {
        mockMvc.perform(internal(get(PATH).param("ids", ids))).andExpect(status().isBadRequest());
    }

    /** Mutation: {@code @RequestParam(required = false)} with a missing list answered as empty content. */
    @Test
    void noIdsParameterIs400() throws Exception {
        mockMvc.perform(internal(get(PATH))).andExpect(status().isBadRequest());
    }

    /** Mutations: MAX_IDS 101; {@code >=} instead of {@code >} (100 refused). */
    @Test
    void oneHundredIdsAreAcceptedAndOneHundredAndOneAreNot() throws Exception {
        StringBuilder hundred = new StringBuilder("1");
        for (int id = 2; id <= 100; id++) {
            hundred.append(',').append(id);
        }

        mockMvc.perform(internal(get(PATH).param("ids", hundred.toString()))).andExpect(status().isOk());
        mockMvc.perform(internal(get(PATH).param("ids", hundred + ",101"))).andExpect(status().isBadRequest());
    }

    /** The limit counts the list as sent. Mutation: count the ids after removing repeats. */
    @Test
    void oneHundredAndOneRepeatsOfOneIdAre400() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");
        String ids = String.join(",", Collections.nCopies(101, Long.toString(merchant.getId())));

        mockMvc.perform(internal(get(PATH).param("ids", ids))).andExpect(status().isBadRequest());
    }

    // ---- the /internal/** chain ----

    /** Mutation: {@code permitAll()} in the internal chain. */
    @Test
    void noKeyIsABare401() throws Exception {
        mockMvc.perform(get(PATH).param("ids", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    /** Mutation: {@code matches} true for any non-empty key. */
    @Test
    void aWrongKeyIs401() throws Exception {
        mockMvc.perform(get(PATH).param("ids", "1").header(InternalApiKey.HEADER, API_KEY + "x"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH).param("ids", "1").header(InternalApiKey.HEADER, ""))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A user JWT, even an admin's, never stands in for the key, and the key
     * counts only in its own header. Mutations: remove the internal chain (the
     * request then reaches the JWT chain, and an admin token gets 200); read the
     * key from the Authorization header.
     */
    @Test
    void aUserJwtOrTheKeyAsABearerTokenIs401() throws Exception {
        mockMvc.perform(bearer(get(PATH).param("ids", "1"), TestTokens.admin()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        mockMvc.perform(bearer(get(PATH).param("ids", "1"), TestTokens.merchant(7, 1)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(bearer(get(PATH).param("ids", "1"), API_KEY))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The key decides; a JWT sent along with it changes nothing. Mutation: the
     * key read from the Authorization header when one is present (the JWT is
     * then taken as the key, and the call is refused).
     */
    @Test
    void theKeyWithAnAdminJwtAlongsideIsStill200() throws Exception {
        mockMvc.perform(bearer(internal(get(PATH).param("ids", "1")), TestTokens.admin()))
                .andExpect(status().isOk());
    }

    /**
     * No CORS headers from /internal/**, for a request with the key or a
     * preflight. Mutation: {@code cors(Customizer.withDefaults())} in the
     * internal chain.
     */
    @Test
    void noCorsHeadersFromInternal() throws Exception {
        mockMvc.perform(internal(get(PATH).param("ids", "1")).header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        mockMvc.perform(options(PATH)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    /**
     * The internal chain matches only /internal/**: the JWT endpoints work as
     * before without the key. Mutation: {@code securityMatcher("/**")}.
     */
    @Test
    void theJwtEndpointsStillNeedOnlyAToken() throws Exception {
        mockMvc.perform(bearer(get("/merchants"), TestTokens.admin())).andExpect(status().isOk());
    }
}
