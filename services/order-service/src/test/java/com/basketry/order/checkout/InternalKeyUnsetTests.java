package com.basketry.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.basketry.order.checkout.CheckoutRefusedException.Unavailable;
import com.basketry.order.config.FingerprintKey;
import com.basketry.order.support.OrderServiceTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Without BASKETRY_INTERNAL_API_KEY every new checkout is refused with a 503
 * before any call: catalog-service and merchant-service would refuse it. A
 * replay needs no call, so it is still answered.
 */
@TestPropertySource(properties = "basketry.internal.api-key=")
class InternalKeyUnsetTests extends OrderServiceTestBase {

    @Autowired
    private FingerprintKey fingerprintKey;

    /** Mutation: the key check removed (catalog-service then answers 401: CATALOG_UNAVAILABLE). */
    @Test
    void aNewCheckoutIsRefusedBeforeAnyCall() {
        givenBuyer();
        givenProduct(10, MERCHANT, "2.50");
        givenMerchant(MERCHANT, "5.00");

        Unavailable refused = catchThrowableOfType(Unavailable.class, () -> checkout(command("key-0001", "5.00", 10, 2)));

        assertThat(refused.code()).isEqualTo("INTERNAL_API_KEY_NOT_CONFIGURED");
        assertThat(services.calls()).isEmpty();
        assertThat(rowCounts()).containsExactly(0, 0, 0, 0);
    }

    /** Mutation: the key checked before the idempotency lookup (the replay is then refused too). */
    @Test
    void aReplayIsStillAnswered() {
        var command = command("key-0001", "5.00", 10, 2);
        String fingerprint = fingerprintKey.hmac(RequestFingerprint.canonical(command)).orElseThrow();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO customer_order (id, order_reference, buyer_account_id, idempotency_key,"
                        + " request_fingerprint, status, needs_attention, total_amount, deadline_at, created_at)"
                        + " VALUES (nextval('customer_order_id_seq'), 'ORD-AAAAAAAAAAAAAAAAAAAA', ?, ?, ?, 'PENDING',"
                        + " FALSE, ?, ?, ?)",
                BUYER, "key-0001", fingerprint, new BigDecimal("5.00"), java.sql.Timestamp.from(now.plusSeconds(120)),
                java.sql.Timestamp.from(now));

        assertThat(checkout(command).created()).isFalse();
        assertThat(services.calls()).isEmpty();
    }
}
