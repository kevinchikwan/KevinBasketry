package com.basketry.merchant.identityclient;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The call to identity-service, which Spring's RestClient logs at DEBUG ("Writing [...]"). */
class MerchantLinkCallTests {

    /** Mutation: MerchantLinkCall's toString with the email. */
    @Test
    void theCallHidesTheEmailAndThePassword() {
        assertThat(new MerchantLinkCall("leak.sentinel.7q@example.com", 5, "Zq7Pw!Lk9xQ").toString())
                .isEqualTo("MerchantLinkCall[email=***, merchantId=5, initialPassword=***]");
    }
}
