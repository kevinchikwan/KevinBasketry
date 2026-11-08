package com.basketry.payment.card;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Every row of the lookup table in DESIGN.md Section 2, payment-service, and the default. */
class CardAuthorizerTests {

    private final CardAuthorizer authorizer = new CardAuthorizer();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "4000000000000002, 00",   // approved
            "4000000000000010, 51",   // insufficient funds
            "4000000000000028, 05",   // do not honor
            "4000000000000036, 54",   // expired card
            "5555555555554444, 00",   // any other number: approved
            "4000000000000044, 00",   // next to the table, still "any other"
            "400000000000001, 00"})   // a table number without its last digit is another number
    void theResponseCodeComesFromTheTable(String cardNumber, String expectedCode) {
        assertThat(authorizer.responseCode(cardNumber)).isEqualTo(expectedCode);
    }
}
