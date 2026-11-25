package com.basketry.promotion.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.promotion.coupon.DiscountType;
import com.basketry.promotion.support.WebTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The body's rules (DESIGN.md Section 3, promotion-service): 1 to 100
 * merchants, each once, a merchant id above 0, a subtotal above 0 with at most
 * 2 decimal places and at most 9,999,999,999.99, and the code through the code
 * rule. A broken rule is a 400 naming every broken rule, checked before any
 * coupon is looked up: never a 409, whatever the code.
 */
class PricingRequestRulesTests extends WebTestBase {

    private static String merchants(int count, String subtotal) {
        List<String> pairs = new ArrayList<>();
        for (int id = 1; id <= count; id++) {
            pairs.add(Integer.toString(id));
            pairs.add(subtotal);
        }
        return body("\"SAVE10\"", pairs.toArray(String[]::new));
    }

    static Stream<Arguments> badCarts() {
        return Stream.of(
                Arguments.of("no merchants", "{\"code\":\"SAVE10\",\"merchants\":[]}"),
                Arguments.of("merchants missing", "{\"code\":\"SAVE10\"}"),
                Arguments.of("merchants null", "{\"code\":\"SAVE10\",\"merchants\":null}"),
                Arguments.of("101 merchants", merchants(101, "1.00")),
                Arguments.of("an empty entry", "{\"code\":\"SAVE10\",\"merchants\":[null]}"),
                Arguments.of("merchant id 0", body("\"SAVE10\"", "0", "1.00")),
                Arguments.of("merchant id -1", body("\"SAVE10\"", "-1", "1.00")),
                Arguments.of("merchant id missing", "{\"code\":\"SAVE10\",\"merchants\":[{\"subtotal\":1.00}]}"),
                Arguments.of("a repeated merchant", body("\"SAVE10\"", "3", "1.00", "3", "2.00")),
                Arguments.of("subtotal 0", body("\"SAVE10\"", "3", "0")),
                Arguments.of("subtotal 0.00", body("\"SAVE10\"", "3", "0.00")),
                Arguments.of("subtotal -1.00", body("\"SAVE10\"", "3", "-1.00")),
                Arguments.of("a third decimal place", body("\"SAVE10\"", "3", "1.005")),
                Arguments.of("above the maximum", body("\"SAVE10\"", "3", "10000000000.00")),
                Arguments.of("subtotal missing", "{\"code\":\"SAVE10\",\"merchants\":[{\"merchantId\":3}]}"));
    }

    /**
     * A coupon exists, so a rule skipped would be priced (200) or reach the
     * service (500). Mutations: {@code merchants.size() > MAX_MERCHANTS + 1};
     * the maximum check removed.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("badCarts")
    void aBadCartIs400AndIsNeverPriced(String name, String body) throws Exception {
        savePlatform("SAVE10", DiscountType.PERCENTAGE, "10");

        price(body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations", hasSize(1)))
                .andExpect(jsonPath("$", not(hasKey("code"))));
    }

    /** The limits are inclusive. Mutation: {@code merchants.size() >= MAX_MERCHANTS}. */
    @Test
    void oneHundredMerchantsAtTheMaximumSubtotalArePriced() throws Exception {
        savePlatform("SAVE10", DiscountType.PERCENTAGE, "10");

        price(merchants(100, "9999999999.99")).andExpect(status().isOk())
                .andExpect(jsonPath("$.merchants", hasSize(100)))
                .andExpect(jsonPath("$.merchants[99].merchantId").value(100));
    }

    /**
     * A code the rule refuses is a 400 at the body, never COUPON_NOT_FOUND
     * (DESIGN.md Section 2, promotion-service, Codes). Mutation: a rule failure
     * answered as 409 COUPON_NOT_FOUND.
     */
    @ParameterizedTest
    @ValueSource(strings = {"\"ab\"", "\"\\u017fave10\"", "\"SAVE 10\"", "\"\"", "\"   \"", "null",
            "\"ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456\""})
    void aCodeTheRuleRefusesIs400(String codeJson) throws Exception {
        price(body(codeJson, "3", "25.50")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations", hasSize(1)))
                .andExpect(jsonPath("$", not(hasKey("code"))));
    }

    /** Every broken rule at once, as order-service's checkout. Mutation: stop at the code's rule. */
    @Test
    void everyBrokenRuleIsNamedAtOnce() throws Exception {
        price("{\"code\":\"ab\",\"merchants\":[{\"merchantId\":0,\"subtotal\":1.005}],\"extra\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The pricing request breaks 4 rule(s)."))
                .andExpect(jsonPath("$.violations", hasSize(4)));
    }
}
