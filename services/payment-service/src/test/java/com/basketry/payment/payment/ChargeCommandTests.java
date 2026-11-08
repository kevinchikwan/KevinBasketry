package com.basketry.payment.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChargeCommandTests {

    private static final String CARD = "4000000000000002";

    private static ChargeCommand command(long orderId, long merchantId, String amount, String card) {
        return new ChargeCommand(orderId, merchantId, amount == null ? null : new BigDecimal(amount), card);
    }

    @Test
    void theAmountIsNormalizedToTwoDecimalPlaces() {
        assertThat(command(1, 1, "10.1", CARD).amount()).isEqualTo(new BigDecimal("10.10"));
        assertThat(command(1, 1, "7", CARD).amount()).isEqualTo(new BigDecimal("7.00"));
        // Trailing zeros are not extra decimal places: 10.100 is 10.10.
        assertThat(command(1, 1, "10.100", CARD).amount()).isEqualTo(new BigDecimal("10.10"));
    }

    @Test
    void theSmallestAndLargestAmountsAreAccepted() {
        assertThat(command(1, 1, "0.01", CARD).amount()).isEqualTo(new BigDecimal("0.01"));
        assertThat(command(1, 1, "9999999999.99", CARD).amount()).isEqualTo(ChargeCommand.MAX_AMOUNT);
    }

    @Test
    void nonPositiveIdsAreRefused() {
        assertInvalid(() -> command(0, 1, "1.00", CARD), "orderId");
        assertInvalid(() -> command(-1, 1, "1.00", CARD), "orderId");
        assertInvalid(() -> command(1, 0, "1.00", CARD), "merchantId");
    }

    @Test
    void aMissingZeroOrNegativeAmountIsRefused() {
        assertInvalid(() -> command(1, 1, null, CARD), "amount is required");
        assertInvalid(() -> command(1, 1, "0", CARD), "greater than 0");
        assertInvalid(() -> command(1, 1, "0.00", CARD), "greater than 0");
        assertInvalid(() -> command(1, 1, "-0.01", CARD), "greater than 0");
    }

    @Test
    void moreThanTwoDecimalPlacesIsRefusedNeverRounded() {
        assertInvalid(() -> command(1, 1, "10.101", CARD), "more than 2 decimal places");
        assertInvalid(() -> command(1, 1, "0.001", CARD), "more than 2 decimal places");
    }

    /** Larger than NUMERIC(12, 2): refused here, so it can never become a database error. */
    @Test
    void anAmountTooLargeForTheColumnIsRefused() {
        assertInvalid(() -> command(1, 1, "10000000000.00", CARD), "larger than");
        assertInvalid(() -> command(1, 1, "9999999999.991", CARD), "more than 2 decimal places");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "40000000002",            // 11 digits
            "40000000000000000002",   // 20 digits
            "4000 0000 0000 0002",
            "4000-0000-0000-0002",
            "400000000000000A",
            "+400000000000002",
            "٤٠٠٠٠٠٠٠٠٠٠٠٠٠٠٢"})      // Arabic-Indic digits: Character.isDigit says yes
    void aMalformedCardNumberIsRefusedWithoutBeingEchoed(String card) {
        assertThatThrownBy(() -> command(1, 1, "1.00", card))
                .isInstanceOf(InvalidPaymentRequestException.class)
                .hasMessageContaining("cardNumber")
                .satisfies(e -> {
                    if (!card.isEmpty()) {
                        assertThat(e.getMessage()).doesNotContain(card);
                    }
                    assertThat(e.getMessage()).doesNotContain("0002");
                });
    }

    @Test
    void aMissingCardNumberIsRefused() {
        assertInvalid(() -> command(1, 1, "1.00", null), "cardNumber");
    }

    @Test
    void twelveAndNineteenDigitsAreAccepted() {
        assertThat(command(1, 1, "1.00", "400000000002").cardLastFour()).isEqualTo("0002");
        assertThat(command(1, 1, "1.00", "4000000000000000002").cardLastFour()).isEqualTo("0002");
    }

    @Test
    void toStringMasksTheCardNumber() {
        String text = command(7, 3, "25.50", CARD).toString();

        assertThat(text).doesNotContain(CARD).doesNotContain("400000000000")
                .contains("cardNumber=************0002").contains("orderId=7").contains("amount=25.50");
    }

    private static void assertInvalid(Runnable construction, String messagePart) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(InvalidPaymentRequestException.class)
                .hasMessageContaining(messagePart);
    }
}
