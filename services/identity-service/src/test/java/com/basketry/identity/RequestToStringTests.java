package com.basketry.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.identity.account.RegisterAccountRequest;
import com.basketry.identity.auth.LoginRequest;
import com.basketry.identity.merchantlink.MerchantLinkRequest;
import org.junit.jupiter.api.Test;

/**
 * The request records show neither the email nor the password in toString,
 * should anything ever log them. Spring no longer does: the controllers read
 * the body themselves (HttpJacksonMessageLeakTests), so the masks are checked
 * here.
 */
class RequestToStringTests {

    private static final String EMAIL = "leak.sentinel.7q@example.com";
    private static final String PASSWORD = "Zq7Pw!Lk9xQ";

    /** Mutation: RegisterAccountRequest's toString with the email. */
    @Test
    void registerHidesTheEmailAndThePassword() {
        assertThat(new RegisterAccountRequest(EMAIL, PASSWORD).toString())
                .isEqualTo("RegisterAccountRequest[email=***, password=***]");
    }

    /** Mutation: LoginRequest's toString with the email. */
    @Test
    void loginHidesTheEmailAndThePassword() {
        assertThat(new LoginRequest(EMAIL, PASSWORD).toString()).isEqualTo("LoginRequest[email=***, password=***]");
    }

    /** Mutation: MerchantLinkRequest's toString with the email. */
    @Test
    void merchantLinkHidesTheEmailAndThePassword() {
        assertThat(new MerchantLinkRequest(EMAIL, 5L, PASSWORD).toString())
                .isEqualTo("MerchantLinkRequest[email=***, merchantId=5, initialPassword=***]");
    }
}
