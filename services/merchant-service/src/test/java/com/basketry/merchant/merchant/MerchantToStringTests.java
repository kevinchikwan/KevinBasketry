package com.basketry.merchant.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The request and answer records show neither the merchant's name nor an
 * email in toString: Spring logs the request it reads and the answer it
 * writes at TRACE. The JSON answers are unchanged.
 */
class MerchantToStringTests {

    private static final String NAME = "Sentinelname Qx7";
    private static final String EMAIL = "leak.sentinel.7q@example.com";
    private static final Instant CREATED = Instant.parse("2026-11-26T00:00:00Z");

    private static MerchantResponse merchant() {
        return new MerchantResponse(1, "MCH-001", NAME, "Groceries", true, true, new BigDecimal("5.00"), EMAIL,
                AccountLinkStatus.LINKED, CREATED);
    }

    /**
     * Mutations: the name shown; the login email shown; the unknown field
     * names shown (they could be a value sent as a name).
     */
    @Test
    void aCreateRequestHidesTheNameTheEmailThePasswordAndTheUnknownNames() {
        CreateMerchantRequest request = new CreateMerchantRequest(NAME, "Groceries", true, new BigDecimal("5.00"),
                EMAIL, "Zq7Pw!Lk9xQ", Map.of("Zq7PwLk9xQr", 1));

        assertThat(request.toString()).isEqualTo("CreateMerchantRequest[name=***, category=Groceries,"
                + " kycVerified=true, commissionRate=5.00, loginEmail=***, initialPassword=***, unknownFields=1]");
    }

    /** Mutation: AccountLinkRequest's toString with the email. */
    @Test
    void anAccountLinkRequestHidesTheEmail() {
        assertThat(new AccountLinkRequest(EMAIL, "Zq7Pw!Lk9xQ").toString())
                .isEqualTo("AccountLinkRequest[email=***, initialPassword=***]");
    }

    /** Mutations: OnboardingResponse's toString with the name; with the login email. */
    @Test
    void anOnboardingAnswerHidesTheNameAndTheEmail() {
        OnboardingResponse response = new OnboardingResponse(1, "MCH-001", NAME, "Groceries", true, true,
                new BigDecimal("5.00"), EMAIL, AccountLinkStatus.LINKED, CREATED, null, null);

        assertThat(response.toString()).isEqualTo("OnboardingResponse[id=1, merchantCode=MCH-001, name=***,"
                + " category=Groceries, kycVerified=true, active=true, commissionRate=5.00, loginEmail=***,"
                + " accountLinkStatus=LINKED, createdAt=2026-11-26T00:00:00Z, accountOutcome=null,"
                + " accountLinkError=null]");
    }

    /** Mutations: MerchantResponse's toString with the name; with the login email. */
    @Test
    void aMerchantAnswerHidesTheNameAndTheEmail() {
        assertThat(merchant().toString()).isEqualTo("MerchantResponse[id=1, merchantCode=MCH-001, name=***,"
                + " category=Groceries, kycVerified=true, active=true, commissionRate=5.00, loginEmail=***,"
                + " accountLinkStatus=LINKED, createdAt=2026-11-26T00:00:00Z]");
    }

    /** The Admin list prints its items with MerchantResponse's toString. Mutation: MerchantResponse's with the name. */
    @Test
    void aPageOfMerchantsHidesTheirNamesAndEmails() {
        assertThat(new MerchantPage(List.of(merchant()), 0, 20, 1, 1).toString())
                .contains("MerchantResponse[id=1, merchantCode=MCH-001, name=***,")
                .doesNotContain(NAME).doesNotContain(EMAIL);
    }
}
