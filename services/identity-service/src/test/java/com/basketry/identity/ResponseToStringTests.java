package com.basketry.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.identity.account.AccountResponse;
import com.basketry.identity.merchantlink.MerchantLinkResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The answer records show no email in toString: Spring logs the object it
 * writes at TRACE ("Writing [...]"); the JSON answer is unchanged.
 */
class ResponseToStringTests {

    private static final String EMAIL = "leak.sentinel.7q@example.com";
    private static final Instant CREATED = Instant.parse("2026-11-26T00:00:00Z");

    /** Mutation: AccountResponse's toString with the email. */
    @Test
    void anAccountHidesTheEmail() {
        assertThat(new AccountResponse(1, EMAIL, true, 5L, true, CREATED).toString()).isEqualTo(
                "AccountResponse[id=1, email=***, buyerActive=true, merchantId=5, merchantActive=true,"
                        + " createdAt=2026-11-26T00:00:00Z]");
    }

    /** Mutation: MerchantLinkResponse's toString with the email. */
    @Test
    void aMerchantLinkHidesTheEmail() {
        assertThat(new MerchantLinkResponse(1, EMAIL, true, 5L, true, CREATED, MerchantLinkResponse.Outcome.CREATED)
                .toString()).isEqualTo("MerchantLinkResponse[id=1, email=***, buyerActive=true, merchantId=5,"
                + " merchantActive=true, createdAt=2026-11-26T00:00:00Z, outcome=CREATED]");
    }
}
