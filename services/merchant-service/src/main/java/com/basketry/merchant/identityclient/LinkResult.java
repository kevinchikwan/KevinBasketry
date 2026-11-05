package com.basketry.merchant.identityclient;

import java.util.List;

/** The answer to one merchant-link call, after its automatic retry if any. */
public sealed interface LinkResult {

    /**
     * Any 2xx from identity-service. {@code outcome} is null only if the body
     * did not carry a known outcome; the call still counts as a success.
     */
    record Linked(AccountOutcome outcome) implements LinkResult {
    }

    /**
     * @param detail     message for Admin
     * @param violations failed password rules for {@link LinkErrorCode#WEAK_PASSWORD}, otherwise null
     */
    record Failed(LinkErrorCode code, String detail, List<String> violations) implements LinkResult {

        static Failed of(LinkErrorCode code) {
            return new Failed(code, code.adminMessage(), null);
        }
    }
}
