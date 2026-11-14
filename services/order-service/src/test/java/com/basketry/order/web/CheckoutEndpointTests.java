package com.basketry.order.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.order.support.FakeServices;
import com.basketry.order.support.TestTokens;
import com.basketry.order.support.WebTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;

/** POST /orders/checkout over the real security chain (DESIGN.md Section 3, order-service). */
class CheckoutEndpointTests extends WebTestBase {

    /**
     * A new order: 202, Location: /orders/{reference}, the buyer's view.
     * Mutations: 200 for a new order; Location left out; the admin view
     * (commission shown to the buyer).
     */
    @Test
    void aNewOrderIs202WithItsLocationAndTheBuyersView() throws Exception {
        givenTheStandardCart();

        postCheckout(BUYER_JWT, "key-0001", standardBody(CARD))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", matchesPattern("/orders/ORD-[A-Z2-7]{20}")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(5.00))
                .andExpect(jsonPath("$.merchants.length()").value(2))
                .andExpect(jsonPath("$.merchants[0].commission").doesNotExist())
                .andExpect(jsonPath("$.lines[0].status").value("REQUESTED"))
                .andExpect(content().string(containsString("\"totalAmount\":5.00")))
                .andExpect(content().string(not(containsString(CARD))));
    }

    /** The same key and body: 200, the same Location. Mutation: 202 for a replay. */
    @Test
    void aReplayIs200WithTheSameLocation() throws Exception {
        givenTheStandardCart();
        String location = postCheckout(BUYER_JWT, "key-0001", standardBody(CARD)).andReturn()
                .getResponse().getHeader("Location");

        postCheckout(BUYER_JWT, "key-0001", standardBody(CARD))
                .andExpect(status().isOk())
                .andExpect(header().string("Location", location));
    }

    /** Mutation: permitAll on /orders/checkout. */
    @Test
    void noTokenIs401() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/orders/checkout")
                        .contentType("application/json").content(standardBody(CARD)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * BUYER is needed: Admin is 403 with no code; a merchant's account (BUYER and
     * MERCHANT) may buy. A broken body is a 400 even for Admin (400 before 403).
     * Mutations: the BUYER check removed; the check before the body.
     */
    @Test
    void checkingOutNeedsBuyerAndTheBodyComesFirst() throws Exception {
        givenTheStandardCart();

        postCheckout(TestTokens.admin(), "key-0001", standardBody(CARD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").doesNotExist());
        postCheckout(TestTokens.admin(), "key-0001", "{}")
                .andExpect(status().isBadRequest());
        services.givenAccount(77, TestTokens.merchant(77, 9), true, 9L);
        postCheckout(TestTokens.merchant(77, 9), "key-0002", standardBody(CARD))
                .andExpect(status().isAccepted());
    }

    /** identity-service never issues a non-numeric sub. Mutation: the sub parsed leniently (500). */
    @Test
    void aTokenWhoseSubjectIsNotAnAccountIs401() throws Exception {
        postCheckout(TestTokens.sign("someone", List.of("BUYER"), null), "key-0001", standardBody(CARD))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("invalid_token")));
    }

    /**
     * An unknown field, at the top or in a line, is named with every other
     * broken rule, and the missing header too, in one 400. Mutations: the
     * any-setter left off the lines; Idempotency-Key required by Spring (a 400
     * without our violations).
     */
    @Test
    void unknownFieldsAndTheMissingHeaderAreNamedWithEveryOtherRule() throws Exception {
        postCheckout(BUYER_JWT, null, "{\"lines\":[{\"productId\":10,\"quantity\":0,\"price\":1}],"
                + "\"cardNumber\":\"12\",\"expectedTotal\":5.00,\"currency\":\"GBP\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations", hasItem("Unknown field(s): currency.")))
                .andExpect(jsonPath("$.violations", hasItem("Unknown field(s): lines[0].price.")))
                .andExpect(jsonPath("$.violations",
                        hasItem("Idempotency-Key must be 8 to 64 characters of A-Z, a-z, 0-9, _ and -.")))
                .andExpect(jsonPath("$.violations", hasItem("lines[0].quantity must be a whole number from 1 to 1000.")))
                .andExpect(jsonPath("$.violations", hasItem("cardNumber must be 12 to 19 digits.")));
    }

    /**
     * A key that is not a plain field name is counted with its path, never
     * echoed: a card number as a key, alone or after a letter, at the top and in
     * a line; a normal unknown name is still named. Mutations: every unknown
     * name echoed; the digit-run check removed ("a" + 16 digits echoed).
     */
    @Test
    void aKeyThatCouldBeACardNumberIsNeverEchoed() throws Exception {
        postCheckout(BUYER_JWT, "key-0001", "{\"lines\":[{\"productId\":10,\"quantity\":1,\"a4929117300568841\":1}],"
                + "\"cardNumber\":\"" + CARD + "\",\"expectedTotal\":2.50,\"4929117300568841\":1,"
                + "\"a4929117300568841\":1,\"currency\":\"GBP\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations", hasItem("Unknown field(s): currency.")))
                .andExpect(jsonPath("$.violations",
                        hasItem("2 unknown field(s) at the top level, with a name that is not shown.")))
                .andExpect(jsonPath("$.violations",
                        hasItem("1 unknown field(s) at lines[0], with a name that is not shown.")))
                .andExpect(content().string(not(containsString("49291173"))));
    }

    /**
     * Each field takes only its own JSON type; the 400 names the field path
     * and never quotes the value. Mutations: the strict string deserializer off
     * cardNumber (a number accepted as text); Jackson's own message passed
     * through (it quotes the card number).
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"lines\":[{\"productId\":10,\"quantity\":1.5}],\"cardNumber\":\"4929117300568841\",\"expectedTotal\":2.50}",
            "{\"lines\":[{\"productId\":\"10\",\"quantity\":1}],\"cardNumber\":\"4929117300568841\",\"expectedTotal\":2.50}",
            "{\"lines\":[{\"productId\":10,\"quantity\":1}],\"cardNumber\":4929117300568841,\"expectedTotal\":2.50}",
            "{\"lines\":[{\"productId\":10,\"quantity\":1}],\"cardNumber\":\"4929117300568841\",\"expectedTotal\":\"2.50\"}",
            "{\"lines\":[{\"productId\":10,\"quantity\":1}],\"couponCode\":5,\"cardNumber\":\"4929117300568841\",\"expectedTotal\":2.50}",
            "{\"lines\":[{\"productId\":99999999999999999999,\"quantity\":1}],\"cardNumber\":\"4929117300568841\",\"expectedTotal\":2.50}"})
    void aWrongJsonTypeIsA400NamingTheFieldButNotTheValue(String body) throws Exception {
        MvcResult result = postCheckout(BUYER_JWT, "key-0001", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString(OrderExceptionHandler.UNREADABLE)))
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .doesNotContain("492911730056");
    }

    /** The path names the field. Mutation: the path left out of the detail. */
    @Test
    void theDetailNamesThePath() throws Exception {
        postCheckout(BUYER_JWT, "key-0001", "{\"lines\":[{\"productId\":10,\"quantity\":1},"
                + "{\"productId\":11,\"quantity\":\"2\"}],\"cardNumber\":\"" + CARD + "\",\"expectedTotal\":2.50}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(OrderExceptionHandler.UNREADABLE + " At: lines[1].quantity."));
    }

    /**
     * Broken JSON, and a string where a number belongs (Jackson's message for
     * that quotes the string: "Cannot coerce String value (...)"): the card
     * number is never echoed. Mutation: Jackson's message as the detail.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"cardNumber\":4929117300568841x}",
            "{\"lines\":[{\"productId\":10,\"quantity\":1}],\"cardNumber\":\"4000000000000002\","
                    + "\"expectedTotal\":\"4929117300568841\"}"})
    void brokenJsonIsNeverEchoed(String body) throws Exception {
        postCheckout(BUYER_JWT, "key-0001", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString(OrderExceptionHandler.UNREADABLE)))
                .andExpect(content().string(not(containsString("492911730056"))));
    }

    /** Mutation: the code left off a coupon 400. */
    @Test
    void aCouponIsCouponsNotSupported() throws Exception {
        postCheckout(BUYER_JWT, "key-0001", "{\"lines\":[{\"productId\":10,\"quantity\":1}],\"couponCode\":\"SAVE\","
                + "\"cardNumber\":\"" + CARD + "\",\"expectedTotal\":2.50}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COUPONS_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.violations", hasItem("couponCode: coupons are not supported yet.")));
    }

    /**
     * The refusals after the body, each with its status and code.
     * Mutations: CART_NOT_PURCHASABLE as 400; the lines left off PRICE_CHANGED;
     * a 503 without its code.
     */
    @Test
    void eachRefusalHasItsStatusAndCode() throws Exception {
        givenTheStandardCart();
        postCheckout(BUYER_JWT, "key-0001", "{\"lines\":[{\"productId\":99,\"quantity\":1}],\"cardNumber\":\""
                + CARD + "\",\"expectedTotal\":1.00}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_NOT_PURCHASABLE"))
                .andExpect(jsonPath("$.lines[0].productId").value(99))
                .andExpect(jsonPath("$.lines[0].reason").value("NOT_FOUND"));
        postCheckout(BUYER_JWT, "key-0002", standardBody(CARD).replace("5.00", "4.99"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.total").value(5.00))
                .andExpect(jsonPath("$.lines[1].unitPrice").value(1.25));
        postCheckout(BUYER_JWT, "key-0003", standardBody(CARD)).andExpect(status().isAccepted());
        postCheckout(BUYER_JWT, "key-0003", standardBody("4000000000000010"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        services.givenAccount(BUYER, BUYER_JWT, false, null);
        postCheckout(BUYER_JWT, "key-0004", standardBody(CARD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BUYER_DEACTIVATED"));
        givenBuyer();
        services.answer(FakeServices.PRODUCTS, 500, "{}");
        postCheckout(BUYER_JWT, "key-0005", standardBody(CARD))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CATALOG_UNAVAILABLE"));
    }
}
