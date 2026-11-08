package com.basketry.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RefundCommandTests {

    private static final String REF = "TXN-0123456789abcdef0123456789abcdef";

    @Test
    void aWellFormedRequestIsAccepted() {
        assertThat(new RefundCommand(7, 3, REF).transactionRef()).isEqualTo(REF);
    }

    @Test
    void nonPositiveIdsAreRefused() {
        assertThatThrownBy(() -> new RefundCommand(0, 3, REF))
                .isInstanceOf(InvalidPaymentRequestException.class).hasMessageContaining("orderId");
        assertThatThrownBy(() -> new RefundCommand(7, -3, REF))
                .isInstanceOf(InvalidPaymentRequestException.class).hasMessageContaining("merchantId");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "TXN-0123456789ABCDEF0123456789ABCDEF",   // uppercase
            "TXN-0123456789abcdef0123456789abcde",    // 31 hex digits
            "TXN-0123456789abcdef0123456789abcdef0",  // 33
            "txn-0123456789abcdef0123456789abcdef",
            "0123456789abcdef0123456789abcdef"})
    void aMalformedReferenceIsRefused(String ref) {
        assertThatThrownBy(() -> new RefundCommand(7, 3, ref))
                .isInstanceOf(InvalidPaymentRequestException.class).hasMessageContaining("transactionRef");
    }
}
