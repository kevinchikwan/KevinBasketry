package com.basketry.order.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.order.support.TestTokens;
import com.basketry.order.support.WebTestBase;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.ResultActions;

/** GET /orders/{reference} and GET /orders: who sees what (DESIGN.md Section 3, order-service). */
class OrderReadEndpointTests extends WebTestBase {

    private static final String MERCHANT_JWT = TestTokens.merchant(30, MERCHANT);

    private ResultActions read(String token, String reference) throws Exception {
        return mvc.perform(bearer(get("/orders/{reference}", reference), token));
    }

    private ResultActions list(String token, String query) throws Exception {
        return mvc.perform(bearer(get("/orders" + query), token));
    }

    private String referenceOf(long id) {
        return orderOf(id).getOrderReference();
    }

    /**
     * The buyer sees its order and every leg, without commission,
     * transactionRef or needsAttention. Mutation: the admin view for the buyer.
     */
    @Test
    void theBuyerSeesItsOrderWithoutCommissionOrRepairState() throws Exception {
        long id = order();
        charged(id, MERCHANT);

        read(BUYER_JWT, referenceOf(id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(referenceOf(id)))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.merchants[0].status").value("CHARGED"))
                .andExpect(jsonPath("$.merchants[0].commission").doesNotExist())
                .andExpect(jsonPath("$.merchants[0].transactionRef").doesNotExist())
                .andExpect(jsonPath("$.needsAttention").doesNotExist())
                .andExpect(jsonPath("$.buyerAccountId").doesNotExist());
    }

    /**
     * Our repair queue is not the buyer's: STUCK reads as the compensation still
     * requested, TIMED_OUT as FAILED with NO_RESULT. Mutation: raw states for the
     * buyer.
     */
    @Test
    void theBuyerSeesStuckAsRequestedAndTimedOutAsFailed() throws Exception {
        long id = order();
        charged(id, MERCHANT);
        passAfter(DEADLINE);
        for (int pass = 0; pass < 4; pass++) {
            passAfter(RESEND);
        }

        read(BUYER_JWT, referenceOf(id))
                .andExpect(jsonPath("$.status").value("FAILING"))
                .andExpect(jsonPath("$.merchants[0].status").value("REFUND_REQUESTED"))
                .andExpect(jsonPath("$.merchants[1].status").value("FAILED"))
                .andExpect(jsonPath("$.merchants[1].reasonCode").value("NO_RESULT"))
                .andExpect(jsonPath("$.lines[0].status").value("RELEASE_REQUESTED"));
        read(TestTokens.admin(), referenceOf(id))
                .andExpect(jsonPath("$.merchants[0].status").value("STUCK"))
                .andExpect(jsonPath("$.needsAttention").value(true))
                .andExpect(jsonPath("$.merchants[0].resends").value(3));
    }

    /**
     * A late charge on a FAILED order: the buyer sees FAILING again, with the
     * refund requested (FR-B18). Mutation: the buyer view hiding a reopen
     * (FAILING mapped to FAILED).
     */
    @Test
    void aLateChargeOnAFailedOrderShowsFailingToTheBuyerAgain() throws Exception {
        long id = order();
        passAfter(DEADLINE);
        released(id, PRODUCT_A);
        released(id, PRODUCT_B);
        read(BUYER_JWT, referenceOf(id)).andExpect(jsonPath("$.status").value("FAILED"));

        charged(id, MERCHANT);

        read(BUYER_JWT, referenceOf(id))
                .andExpect(jsonPath("$.status").value("FAILING"))
                .andExpect(jsonPath("$.merchants[0].status").value("REFUND_REQUESTED"));
    }

    /**
     * A merchant sees its own leg and lines only, the buyer's account id and
     * not the order total. Mutations: every line shown to the merchant; the
     * order total shown.
     */
    @Test
    void aMerchantSeesOnlyItsOwnPart() throws Exception {
        long id = order();

        read(MERCHANT_JWT, referenceOf(id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyerAccountId").value((int) BUYER))
                .andExpect(jsonPath("$.totalAmount").doesNotExist())
                .andExpect(jsonPath("$.charge.amount").value(2.50))
                .andExpect(jsonPath("$.charge.commission").value(0.13))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].productId").value((int) PRODUCT_A));
    }

    /**
     * A non-admin gets 404 for a missing order and for one that is not theirs;
     * a merchant whose token lost MERCHANT (deactivated) too. Admin gets 404 only
     * for a missing one. Mutation: 403 for a foreign order (it would tell the
     * order exists).
     */
    @Test
    void notYoursIsNotFound() throws Exception {
        long id = order();
        String missing = "ORD-AAAAAAAAAAAAAAAAAAAA";

        read(TestTokens.buyer(99), referenceOf(id)).andExpect(status().isNotFound());
        read(TestTokens.merchant(31, 77), referenceOf(id)).andExpect(status().isNotFound());
        read(TestTokens.buyer(30), referenceOf(id)).andExpect(status().isNotFound());
        read(BUYER_JWT, missing).andExpect(status().isNotFound());
        read(TestTokens.admin(), missing).andExpect(status().isNotFound());
        read(TestTokens.admin(), referenceOf(id)).andExpect(status().isOk());
    }

    /** A malformed reference is a 400 for anyone. Mutation: the format check removed (404). */
    @ParameterizedTest
    @ValueSource(strings = {"ORD-1", "ord-AAAAAAAAAAAAAAAAAAAA", "ORD-AAAAAAAAAAAAAAAAAAA1"})
    void aMalformedReferenceIs400(String reference) throws Exception {
        read(TestTokens.admin(), reference).andExpect(status().isBadRequest());
    }

    /**
     * A buyer's list: its own orders, newest first, as summaries. Mutations:
     * oldest first; another buyer's orders included.
     */
    @Test
    void aBuyersListIsItsOwnNewestFirst() throws Exception {
        long first = order();
        clock.advance(Duration.ofSeconds(1));
        long second = order();
        services.givenAccount(55, TestTokens.buyer(55), true, null);
        checkoutService.checkout(command("key-other", "2.50", PRODUCT_A, 1),
                new com.basketry.order.checkout.CheckoutService.Buyer(55, TestTokens.buyer(55)));

        list(BUYER_JWT, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].reference").value(referenceOf(second)))
                .andExpect(jsonPath("$.content[1].reference").value(referenceOf(first)))
                .andExpect(jsonPath("$.content[0].lineCount").value(2))
                .andExpect(jsonPath("$.content[0].totalAmount").value(5.00))
                .andExpect(jsonPath("$.content[0].needsAttention").doesNotExist())
                .andExpect(jsonPath("$.content[0].lines").doesNotExist());
    }

    /**
     * view=merchant: the orders with its lines, its own amount and line count.
     * Mutation: the order total for the merchant's amount.
     */
    @Test
    void aMerchantsListShowsItsOwnAmount() throws Exception {
        order();

        list(MERCHANT_JWT, "?view=merchant")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].totalAmount").value(2.50))
                .andExpect(jsonPath("$.content[0].lineCount").value(1));
        list(TestTokens.merchant(31, 77), "?view=merchant").andExpect(jsonPath("$.totalElements").value(0));
    }

    /**
     * Admin: every order, or only those needing attention. Mutation: the
     * needsAttention filter ignored.
     */
    @Test
    void adminListsEveryOrderOrTheRepairQueue() throws Exception {
        long stuck = order();
        order();
        passAfter(DEADLINE);
        for (int pass = 0; pass < 4; pass++) {
            passAfter(RESEND);
        }
        order();

        list(TestTokens.admin(), "").andExpect(jsonPath("$.totalElements").value(3));
        list(TestTokens.admin(), "?needsAttention=true")
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[1].reference").value(referenceOf(stuck)))
                .andExpect(jsonPath("$.content[0].needsAttention").value(true));
    }

    /**
     * The wrong parameter for the reader is a 403; a bad value a 400 first.
     * Mutations: needsAttention allowed for a buyer; view=merchant without
     * MERCHANT allowed (an empty page); a parameter's form checked after the
     * capability.
     */
    @Test
    void theWrongParameterForTheReader() throws Exception {
        list(BUYER_JWT, "?needsAttention=true").andExpect(status().isForbidden());
        list(TestTokens.admin(), "?view=buyer").andExpect(status().isForbidden());
        list(BUYER_JWT, "?view=merchant").andExpect(status().isForbidden());
        list(BUYER_JWT, "?needsAttention=maybe").andExpect(status().isBadRequest());
        list(TestTokens.admin(), "?view=shop").andExpect(status().isBadRequest());
    }

    /** Mutations: size 101 accepted; page -1 accepted. */
    @ParameterizedTest
    @ValueSource(strings = {"?size=0", "?size=101", "?page=-1", "?page=x", "?size=1.5"})
    void pagingOutOfRangeIs400(String query) throws Exception {
        list(BUYER_JWT, query).andExpect(status().isBadRequest());
    }
}
