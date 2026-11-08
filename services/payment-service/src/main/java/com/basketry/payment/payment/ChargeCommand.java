package com.basketry.payment.payment;

import com.basketry.payment.card.CardNumbers;
import java.math.BigDecimal;

/**
 * One charge request: {@code order.merchant-charge.requested} without its
 * transport (DESIGN.md Section 5). Validated on construction, so an invalid
 * request never reaches the database. {@code amount} is normalized to 2
 * decimal places.
 *
 * <p>{@code cardNumber} is the raw number from the contract. {@link #toString}
 * masks it, and no exception message ever contains it.
 */
public record ChargeCommand(long orderId, long merchantId, BigDecimal amount, String cardNumber) {

    /** The largest value of the NUMERIC(12, 2) column. A larger amount would be a database error. */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    public ChargeCommand {
        if (orderId <= 0) {
            throw new InvalidPaymentRequestException("orderId must be positive");
        }
        if (merchantId <= 0) {
            throw new InvalidPaymentRequestException("merchantId must be positive");
        }
        if (amount == null) {
            throw new InvalidPaymentRequestException("amount is required");
        }
        if (amount.signum() <= 0) {
            throw new InvalidPaymentRequestException("amount must be greater than 0");
        }
        // 10.100 is fine (it is 10.10); 10.101 is refused, never rounded.
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new InvalidPaymentRequestException("amount has more than 2 decimal places");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidPaymentRequestException("amount is larger than " + MAX_AMOUNT);
        }
        // The message never contains the number, not even part of it.
        if (!CardNumbers.isWellFormed(cardNumber)) {
            throw new InvalidPaymentRequestException("cardNumber must be " + CardNumbers.MIN_LENGTH + " to "
                    + CardNumbers.MAX_LENGTH + " digits");
        }
        amount = amount.setScale(2);
    }

    public String cardLastFour() {
        return CardNumbers.lastFour(cardNumber);
    }

    @Override
    public String toString() {
        return "ChargeCommand[orderId=" + orderId + ", merchantId=" + merchantId + ", amount=" + amount
                + ", cardNumber=" + CardNumbers.mask(cardNumber) + "]";
    }
}
