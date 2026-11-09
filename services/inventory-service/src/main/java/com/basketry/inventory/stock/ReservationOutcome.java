package com.basketry.inventory.stock;

/** The answer to a reserve request: what was published for it. */
public sealed interface ReservationOutcome {

    record Reserved() implements ReservationOutcome {
    }

    record ReservationFailed(ReasonCode reasonCode) implements ReservationOutcome {
    }
}
