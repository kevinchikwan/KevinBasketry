package com.basketry.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.basketry.order.checkout.CheckoutRefusedException.Unavailable;
import com.basketry.order.support.OrderServiceTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Without BASKETRY_ORDER_FINGERPRINT_KEY the service starts and refuses every
 * checkout with a fixed 503, before calling anything (DESIGN.md Section 2,
 * order-service, checkout step 3). Mutation: {@code orElse("")} for the
 * missing fingerprint (the checkout then goes through, unprotected).
 */
@TestPropertySource(properties = "basketry.order.fingerprint-key=")
class FingerprintKeyUnsetTests extends OrderServiceTestBase {

    @Test
    void everyCheckoutIsRefusedBeforeAnyCall() {
        givenBuyer();
        givenProduct(10, MERCHANT, "2.50");
        givenMerchant(MERCHANT, "5.00");

        Unavailable refused = catchThrowableOfType(Unavailable.class, () -> checkout(command("key-0001", "5.00", 10, 2)));

        assertThat(refused.code()).isEqualTo("FINGERPRINT_KEY_NOT_CONFIGURED");
        assertThat(services.calls()).isEmpty();
        assertThat(rowCounts()).containsExactly(0, 0, 0, 0);
    }
}
