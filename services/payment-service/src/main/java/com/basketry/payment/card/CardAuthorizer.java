package com.basketry.payment.card;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The deterministic mock authorizer (DESIGN.md Section 2, payment-service):
 * the result depends only on the card number, looked up in a fixed table.
 * Any number not in the table is approved.
 */
@Component
public class CardAuthorizer {

    public static final String APPROVED = "00";
    public static final String INSUFFICIENT_FUNDS = "51";
    public static final String DO_NOT_HONOR = "05";
    public static final String EXPIRED_CARD = "54";

    private static final Map<String, String> DECLINES = Map.of(
            "4000000000000010", INSUFFICIENT_FUNDS,
            "4000000000000028", DO_NOT_HONOR,
            "4000000000000036", EXPIRED_CARD);

    /** The ISO 8583-style response code for this card: "00" approves, anything else declines. */
    public String responseCode(String cardNumber) {
        return DECLINES.getOrDefault(cardNumber, APPROVED);
    }
}
