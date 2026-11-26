package com.basketry.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.identity.account.AccountResponse;
import com.basketry.identity.auth.LoginResponse;
import com.basketry.identity.merchantlink.MerchantLinkResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The answer records show no email and no access token in toString: Spring
 * logs the object it writes at TRACE ("Writing [...]"); the JSON answer is
 * unchanged.
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

    /** Mutation: LoginResponse's toString with the access token. */
    @Test
    void aLoginAnswerHidesTheAccessToken() {
        assertThat(new LoginResponse("eyJ.token-sentinel-7q.sig", "Bearer", 900).toString())
                .isEqualTo("LoginResponse[accessToken=***, tokenType=Bearer, expiresIn=900]");
    }
}
