package com.basketry.promotion.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.promotion.support.CouponApiTestBase;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Every answer of GET /coupons other than a page (ROADMAP A7), body for body:
 * 401, 403, 400 for the paging parameters, and the 405 of /coupons, which
 * now allows GET and POST.
 */
class CouponListAnswersTests extends CouponApiTestBase {

    private static final String PAGE = "page must be a whole number, 0 or more.";
    private static final String SIZE = "size must be a whole number from 1 to 100.";

    private ResultActions list(String token, String query) throws Exception {
        return mvc.perform(get("/coupons" + query).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static String invalid(String instance, String... violations) {
        return CouponCreateAnswersTests.invalid(instance, violations);
    }

    /** Mutation: GET /coupons permitted without a token in the chain. */
    @Test
    void noTokenIs401() throws Exception {
        mvc.perform(get("/coupons")).andExpect(status().isUnauthorized()).andExpect(content().string(""));
    }

    /** Before the paging parameters are read. Mutation: the capability check after them. */
    @Test
    void aBuyerIs403() throws Exception {
        CouponCreateAnswersTests.expect(list(BUYER_TOKEN, "?page=abc"), 403, CouponCreateAnswersTests.problem(403,
                "Forbidden", "Only Admin and merchants can manage coupons.", "/coupons"));
    }

    /**
     * Out of range or not a whole number: a 400 naming the parameter, never
     * clamped and never Spring's conversion message (which quotes the value).
     * Mutation: page parsed with Integer.parseInt and clamped at 0.
     */
    @ParameterizedTest
    @ValueSource(strings = {"-1", "abc", "1.5", "", "+1", "1234567890"})
    void aPageThatIsNotAWholeNumberFromZeroIs400(String page) throws Exception {
        CouponCreateAnswersTests.expect(list(ADMIN_TOKEN, "?page=" + page), 400, invalid("/coupons", PAGE));
    }

    /** Mutation: size parsed with Integer.parseInt and clamped at 100. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-5", "abc", "20.0", ""})
    void aSizeOutsideOneToOneHundredIs400(String size) throws Exception {
        CouponCreateAnswersTests.expect(list(ADMIN_TOKEN, "?size=" + size), 400, invalid("/coupons", SIZE));
    }

    /** Both at once. Mutation: the size not checked once the page is wrong. */
    @Test
    void bothParametersAreNamedAtOnce() throws Exception {
        CouponCreateAnswersTests.expect(list(MERCHANT_TOKEN, "?page=-1&size=0"), 400, invalid("/coupons", PAGE, SIZE));
    }

    /** Mutation: GET /coupons mapped for any method. */
    @Test
    void anotherMethodOnTheCollectionIs405WithGetAndPost() throws Exception {
        MvcResult result = mvc.perform(put("/coupons").header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().json(CouponCreateAnswersTests.problem(405, "Method Not Allowed",
                        "Method 'PUT' is not supported.", "/coupons"), JsonCompareMode.STRICT))
                .andReturn();
        // The order of Allow is not fixed.
        Set<String> allowed = Arrays.stream(result.getResponse().getHeader(HttpHeaders.ALLOW).split(","))
                .map(String::strip).collect(Collectors.toSet());
        assertThat(allowed).isEqualTo(Set.of("GET", "POST"));
    }
}
