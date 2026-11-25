package com.basketry.promotion.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.coupon.CouponCode;
import com.basketry.promotion.coupon.CouponScope;
import com.basketry.promotion.coupon.Discount;
import com.basketry.promotion.coupon.DiscountType;
import com.basketry.promotion.support.PricingContract;
import com.basketry.promotion.support.WebTestBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * A full request through MockMvc, from a coupon saved through JPA to the
 * priced answer, on H2 and (postgresTest) on PostgreSQL, against
 * docs/contracts/coupon-pricing.json: the file order-service's client test
 * reads too.
 */
class PricingContractTests extends WebTestBase {

    private JsonNode contract;

    @BeforeEach
    void readTheContract() throws Exception {
        contract = PricingContract.read();
    }

    /** The coupon the contract describes, saved with the given expiry. */
    private Coupon saveTheContractsCoupon(Instant expiresAt) {
        JsonNode coupon = contract.get("coupon");
        return coupons.save(new Coupon(new CouponCode(coupon.get("code").asString()),
                CouponScope.valueOf(coupon.get("scope").asString()), null,
                new Discount(DiscountType.valueOf(coupon.get("discountType").asString()),
                        coupon.get("discountValue").decimalValue()),
                expiresAt));
    }

    private MvcResult postTheRequest() throws Exception {
        return price(PricingContract.JSON.writeValueAsString(contract.get("request"))).andReturn();
    }

    private void assertAnswer(MvcResult result, JsonNode expected) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(expected.get("status").asInt());
        PricingContract.assertSameJson(PricingContract.JSON.readTree(result.getResponse().getContentAsString()),
                expected.get("body"));
    }

    /**
     * The contract's request gets its 200, field by field and at scale 2.
     * Mutation: the answer's field named {@code amount} instead of {@code discount}.
     */
    @Test
    void theRequestIsPricedAsTheContractSays() throws Exception {
        saveTheContractsCoupon(FAR_FUTURE);

        MvcResult result = postTheRequest();

        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertAnswer(result, contract.get("ok"));
    }

    /**
     * Each refusal is the contract's 409, with the reason as {@code code} and
     * the detail never quoting the coupon code. Mutation: the {@code code}
     * property left off the 409.
     */
    @Test
    void eachRefusalIsTheContractsConflict() throws Exception {
        JsonNode refusals = contract.get("refusals");
        assertThat(refusals.propertyNames()).containsExactlyInAnyOrder(
                "COUPON_NOT_FOUND", "COUPON_INACTIVE", "COUPON_EXPIRED", "COUPON_NOT_APPLICABLE");

        // No coupon has the code.
        assertAnswer(postTheRequest(), refusals.get("COUPON_NOT_FOUND"));

        Coupon inactive = saveTheContractsCoupon(FAR_FUTURE);
        inactive.deactivate();
        coupons.save(inactive);
        assertAnswer(postTheRequest(), refusals.get("COUPON_INACTIVE"));

        jdbc.update("DELETE FROM coupon");
        saveTheContractsCoupon(Instant.now().minus(1, ChronoUnit.DAYS));
        assertAnswer(postTheRequest(), refusals.get("COUPON_EXPIRED"));

        jdbc.update("DELETE FROM coupon");
        // A merchant coupon of a merchant not in the request's cart.
        saveMerchant(contract.get("request").get("code").asString(), 9, DiscountType.PERCENTAGE, "10");
        MvcResult notApplicable = postTheRequest();
        assertAnswer(notApplicable, refusals.get("COUPON_NOT_APPLICABLE"));
        assertThat(notApplicable.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }

    /**
     * One entry per merchant, in the order sent, not in id order. Mutation:
     * the answer sorted by merchantId.
     */
    @Test
    void theAnswerKeepsTheOrderSent() throws Exception {
        savePlatform("SAVE10", DiscountType.PERCENTAGE, "10");

        price(body("\"SAVE10\"", "5", "10.00", "3", "25.50", "4", "1.00"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"merchants\":[{\"merchantId\":5,\"discount\":1.00},"
                        + "{\"merchantId\":3,\"discount\":2.55},{\"merchantId\":4,\"discount\":0.10}]}"));
    }

    /**
     * 2.5 and 2.50 are the same subtotal, and every discount is written at
     * scale 2. Mutation: the answer's discount with stripTrailingZeros().
     */
    @Test
    void discountsAreWrittenAtScaleTwo() throws Exception {
        savePlatform("ALLOFF", DiscountType.PERCENTAGE, "100");

        price(body("\"ALLOFF\"", "1", "2.5", "2", "2.50", "3", "7"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"merchants\":[{\"merchantId\":1,\"discount\":2.50},"
                        + "{\"merchantId\":2,\"discount\":2.50},{\"merchantId\":3,\"discount\":7.00}]}"));
    }

    /** The code as typed is normalised first. Mutation: {@code new CouponCode(code)} instead of normalise. */
    @Test
    void aCodeAsTypedIsNormalised() throws Exception {
        savePlatform("SAVE10", DiscountType.PERCENTAGE, "10");

        for (String typed : Map.of("lower", "\"save10\"", "spaced", "\" Save10\\n\"").values()) {
            price(body(typed, "3", "25.50")).andExpect(status().isOk())
                    .andExpect(content().string("{\"merchants\":[{\"merchantId\":3,\"discount\":2.55}]}"));
        }
    }
}
