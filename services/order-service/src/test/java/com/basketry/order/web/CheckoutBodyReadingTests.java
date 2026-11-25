package com.basketry.order.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.order.support.TestTokens;
import com.basketry.order.support.WebTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * CheckoutController reads the body itself (HttpJacksonMessageLeakTests): what
 * Spring's JSON converter did before must still hold. Only JSON is read, an
 * unreadable body is a 400 before the token's subject and the BUYER check, and
 * the JSON literal null is a missing body.
 */
class CheckoutBodyReadingTests extends WebTestBase {

    /** Mutation: {@code consumes} left off the mapping (a byte[] body accepts any type). */
    @Test
    void aBodyThatIsNotJsonIs415() throws Exception {
        givenTheStandardCart();

        mvc.perform(bearer(post("/orders/checkout"), BUYER_JWT)
                        .header("Idempotency-Key", "key-0001")
                        .contentType("text/plain").content(standardBody(CARD)))
                .andExpect(status().isUnsupportedMediaType());
    }

    /**
     * Unreadable JSON is a 400 even for a token whose subject is not an account
     * and for Admin, as when Spring read the body before the method ran.
     * Mutations: the body read after the subject check (401); read after the
     * BUYER check (403).
     */
    @Test
    void anUnreadableBodyIs400BeforeTheSubjectAndTheBuyerCheck() throws Exception {
        postCheckout(TestTokens.sign("someone", List.of("BUYER"), null), "key-0001", "{x")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(OrderExceptionHandler.UNREADABLE));
        postCheckout(TestTokens.admin(), "key-0002", "{x")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(OrderExceptionHandler.UNREADABLE));
    }

    /** As Spring answered it: a missing body. Mutation: the null check removed (a 500). */
    @Test
    void theJsonLiteralNullIs400() throws Exception {
        postCheckout(BUYER_JWT, "key-0001", "null")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(OrderExceptionHandler.UNREADABLE));
    }

    /** No body at all: the fixed detail. Mutation: the handler's override removed (Spring's own detail). */
    @Test
    void anEmptyBodyIs400WithTheFixedDetail() throws Exception {
        postCheckout(BUYER_JWT, "key-0001", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(OrderExceptionHandler.UNREADABLE));
    }
}
