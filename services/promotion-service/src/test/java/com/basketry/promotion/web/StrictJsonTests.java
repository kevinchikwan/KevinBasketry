package com.basketry.promotion.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.promotion.coupon.DiscountType;
import com.basketry.promotion.support.WebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Strict JSON from the first endpoint (DESIGN.md Section 3, promotion-service):
 * each field takes only its own JSON type, an unknown field is a 400 that never
 * names it, a field given twice and anything after the body are refused, and
 * no detail ever quotes the input. A coupon exists, so anything let through
 * would be priced (200) instead.
 */
class StrictJsonTests extends WebTestBase {

    private static final String SECRET = "SECRETCODE7Q";

    @BeforeEach
    void aCoupon() {
        savePlatform("SAVE10", DiscountType.PERCENTAGE, "10");
    }

    /** Mutation: {@code allow-coercion-of-scalars: true}. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"code\":\"SAVE10\",\"merchants\":[{\"merchantId\":\"3\",\"subtotal\":25.50}]}",
            "{\"code\":\"SAVE10\",\"merchants\":[{\"merchantId\":3,\"subtotal\":\"25.50\"}]}"})
    void aNumberSentAsAStringIs400(String body) throws Exception {
        price(body).andExpect(status().isBadRequest());
    }

    /** Mutation: {@code accept-float-as-int: true}. */
    @ParameterizedTest
    @ValueSource(strings = {"3.0", "3.5", "3e0"})
    void aFractionForTheMerchantIdIs400(String merchantId) throws Exception {
        price(body("\"SAVE10\"", merchantId, "25.50")).andExpect(status().isBadRequest());
    }

    /** Mutation: no StrictStringDeserializer on {@code code}. */
    @ParameterizedTest
    @ValueSource(strings = {"123456", "true", "12345.5"})
    void aCodeThatIsNotAStringIs400(String codeJson) throws Exception {
        price(body(codeJson, "3", "25.50")).andExpect(status().isBadRequest());
    }

    /** Mutation: unknown fields ignored (the any-setter's map never checked). */
    @Test
    void anUnknownFieldIs400AtTheTopAndInALine() throws Exception {
        price("{\"code\":\"SAVE10\",\"merchants\":[{\"merchantId\":3,\"subtotal\":25.50}],\"note\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0]").value(
                        "1 unknown field(s) at the top level, with a name that is not shown."));
        price("{\"code\":\"SAVE10\",\"merchants\":[{\"merchantId\":3,\"subtotal\":25.50,\"note\":1}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0]").value(
                        "1 unknown field(s) at merchants[0], with a name that is not shown."));
    }

    /** A field name could be a coupon code. Mutation: the unknown names listed in the violation. */
    @Test
    void anUnknownFieldIsNeverNamed() throws Exception {
        price("{\"code\":\"SAVE10\",\"" + SECRET + "\":1,\"merchants\":[{\"merchantId\":3,\"subtotal\":25.50,\""
                + SECRET + "X\":1}]}")
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString(SECRET))));
    }

    /** Mutation: FAIL_ON_TRAILING_TOKENS disabled in StrictJsonConfig. */
    @ParameterizedTest
    @ValueSource(strings = {" x", "}", "{}", " null", "[]"})
    void anythingAfterTheBodyIs400(String trailing) throws Exception {
        price(body("\"SAVE10\"", "3", "25.50") + trailing).andExpect(status().isBadRequest());
    }

    /** The last value would otherwise win. Mutation: STRICT_DUPLICATE_DETECTION not enabled. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"code\":\"NOSUCHCODE\",\"code\":\"SAVE10\",\"merchants\":[{\"merchantId\":3,\"subtotal\":25.50}]}",
            "{\"code\":\"SAVE10\",\"merchants\":[{\"merchantId\":3,\"subtotal\":1.00,\"subtotal\":25.50}]}",
            "{\"code\":\"SAVE10\",\"merchants\":[{\"merchantId\":4,\"merchantId\":3,\"subtotal\":25.50}]}"})
    void aRepeatedFieldIs400(String body) throws Exception {
        price(body).andExpect(status().isBadRequest());
    }

    /**
     * A repeated field inside an unknown field's value puts the unknown name in
     * Jackson's path; it is shown as "?". Mutation: every name in the path shown.
     */
    @Test
    void aRepeatedUnknownNameIsNeverShown() throws Exception {
        price("{\"code\":\"SAVE10\",\"" + SECRET + "\":1,\"" + SECRET + "\":2,\"merchants\":[]}")
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString(SECRET))));
        price("{\"code\":\"SAVE10\",\"merchants\":[{\"merchantId\":3,\"subtotal\":1.00,\"" + SECRET + "\":1,\""
                + SECRET + "\":2}]}")
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString(SECRET))));
        price("{\"code\":\"SAVE10\",\"merchants\":[{\"merchantId\":3,\"subtotal\":1.00,\"" + SECRET
                + "\":{\"a\":1,\"a\":2}}]}")
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString(SECRET))));
    }

    /** No body at all: the fixed detail, never Spring's message. Mutation: the handler's override removed. */
    @Test
    void anEmptyBodyIs400WithTheFixedDetail() throws Exception {
        price("").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(UnreadableBodyException.UNREADABLE));
    }

    /** The JSON literal null is not a request. Mutation: the null check removed (a 500). */
    @Test
    void aNullBodyIs400() throws Exception {
        price("null").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0]").value("The body must be a JSON object."));
    }

    /**
     * Broken JSON gets a fixed detail and the field path, never Jackson's
     * message, which quotes the input. Mutation: Jackson's message as the detail.
     */
    @Test
    void brokenJsonIsNeverEchoed() throws Exception {
        price("{\"code\":\"" + SECRET + "\",\"merchants\":[{\"merchantId\":3,\"subtotal\":2x")
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString(SECRET))));
        price("{\"code\":\"SAVE10\",\"merchants\":[{\"merchantId\":3,\"subtotal\":\"" + SECRET + "\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(UnreadableBodyException.UNREADABLE
                        + " At: merchants[0].subtotal."))
                .andExpect(content().string(not(containsString(SECRET))));
    }
}
