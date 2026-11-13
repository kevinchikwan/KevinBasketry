package com.basketry.order.downstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.basketry.order.downstream.CatalogClient.CatalogProduct;
import com.basketry.order.downstream.CatalogClient.StockRow;
import com.basketry.order.downstream.IdentityClient.AccountNotFoundException;
import com.basketry.order.downstream.MerchantClient.MerchantStatus;
import com.basketry.order.support.FakeServices;
import com.basketry.order.support.OrderServiceTestBase;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The three clients over real HTTP (DESIGN.md Section 3, order-service): what
 * they read, and that anything but a readable answer is the service's 503 code.
 */
@ExtendWith(OutputCaptureExtension.class)
class DownstreamClientTests extends OrderServiceTestBase {

    @Autowired
    private IdentityClient identity;

    @Autowired
    private CatalogClient catalog;

    @Autowired
    private MerchantClient merchantClient;

    private static String unavailableCode(Runnable call) {
        return catchThrowableOfType(DownstreamUnavailableException.class, call::run).code();
    }

    /**
     * Mutations: the null check of merchantId removed (a buyer with no merchant
     * link becomes unreadable); buyerActive read as always true.
     */
    @Test
    void identityAnswersBuyerActiveAndTheMerchantLink() {
        services.givenAccount(12, "t12", true, null);
        services.givenAccount(13, "t13", false, 3L);

        assertThat(identity.account(12, "t12")).isEqualTo(new IdentityClient.BuyerAccount(true, OptionalLong.empty()));
        assertThat(identity.account(13, "t13")).isEqualTo(new IdentityClient.BuyerAccount(false, OptionalLong.of(3)));
    }

    /** Mutation: 404 handled as unexpected (IDENTITY_UNAVAILABLE instead of BUYER_NOT_FOUND). */
    @Test
    void anUnknownAccountIsNotFound() {
        assertThatThrownBy(() -> identity.account(99, "t99")).isInstanceOf(AccountNotFoundException.class);
    }

    /**
     * identity-service refusing the forwarded token is a 503, logged as an
     * ERROR; a 5xx is a WARN. Mutations: 401 taken as BUYER_NOT_FOUND; no ERROR.
     */
    @Test
    void identityRefusingTheTokenIsUnavailableAndAnError(CapturedOutput output) {
        services.givenAccount(12, "t12", true, null);

        assertThat(unavailableCode(() -> identity.account(12, "not-t12"))).isEqualTo("IDENTITY_UNAVAILABLE");
        assertThat(output).contains("ERROR").contains("identity-service answered 401");
    }

    /** Mutation: a string "true" accepted as a boolean. */
    @Test
    void aBuyerActiveOfTheWrongTypeIsUnreadable(CapturedOutput output) {
        services.answer(FakeServices.ACCOUNTS, 200, "{\"id\":12,\"buyerActive\":\"true\",\"merchantId\":null}");

        assertThat(unavailableCode(() -> identity.account(12, "t12"))).isEqualTo("IDENTITY_UNAVAILABLE");
        assertThat(output).contains("ERROR").contains("field buyerActive is missing or has the wrong type");
    }

    /** Every field order-service checks, by id, the price at scale 2. Mutation: the price kept at the sent scale. */
    @Test
    void catalogAnswersEveryProductItKnows() {
        services.answer(FakeServices.PRODUCTS, 200, "{\"content\":[{\"id\":10,\"merchantId\":3,\"name\":\"Tea\","
                + "\"price\":2.5,\"active\":true,\"merchantActive\":false,\"stockRow\":\"REJECTED\"}]}");

        Map<Long, CatalogProduct> products = catalog.products(List.of(10L, 11L));

        assertThat(products).containsOnlyKeys(10L);
        assertThat(products.get(10L)).isEqualTo(new CatalogProduct(10, 3, "Tea", new BigDecimal("2.50"), true, false,
                StockRow.REJECTED));
        assertThat(products.get(10L).price().scale()).isEqualTo(2);
    }

    /**
     * An answer catalog-service would never give is unreadable: CATALOG_UNAVAILABLE
     * and an ERROR, never a guess. Mutations: an unknown stockRow taken as
     * CREATED; a price with 3 decimals rounded; a missing field defaulted.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"content\":[{\"id\":10,\"merchantId\":3,\"name\":\"Tea\",\"price\":2.50,\"active\":true,"
                    + "\"merchantActive\":true,\"stockRow\":\"SOMETHING\"}]}",
            "{\"content\":[{\"id\":10,\"merchantId\":3,\"name\":\"Tea\",\"price\":2.505,\"active\":true,"
                    + "\"merchantActive\":true,\"stockRow\":\"CREATED\"}]}",
            "{\"content\":[{\"id\":10,\"merchantId\":3,\"name\":\"Tea\",\"price\":2.50,"
                    + "\"merchantActive\":true,\"stockRow\":\"CREATED\"}]}",
            "{\"content\":[{\"id\":10,\"merchantId\":3,\"name\":\"Tea\",\"price\":\"2.50\",\"active\":true,"
                    + "\"merchantActive\":true,\"stockRow\":\"CREATED\"}]}",
            "{\"items\":[]}",
            "not json"})
    void anAnswerCatalogNeverGivesIsUnreadable(String body, CapturedOutput output) {
        services.answer(FakeServices.PRODUCTS, 200, body);

        assertThat(unavailableCode(() -> catalog.products(List.of(10L)))).isEqualTo("CATALOG_UNAVAILABLE");
        assertThat(output).contains("ERROR").contains("catalog-service gave an answer order-service cannot read");
    }

    /**
     * A 401 means the keys differ (an ERROR naming the variable); a 400 means a
     * bug (an ERROR); a 5xx is an outage (a WARN). Mutation: a 4xx logged at WARN.
     */
    @Test
    void catalogStatusesOtherThan200(CapturedOutput output) {
        services.answer(FakeServices.PRODUCTS, 401, "");
        assertThat(unavailableCode(() -> catalog.products(List.of(10L)))).isEqualTo("CATALOG_UNAVAILABLE");
        assertThat(output).contains("ERROR").contains("BASKETRY_INTERNAL_API_KEY values must match");

        services.answer(FakeServices.PRODUCTS, 400, "{}");
        assertThat(unavailableCode(() -> catalog.products(List.of(10L)))).isEqualTo("CATALOG_UNAVAILABLE");
        assertThat(output.getAll().lines().filter(line -> line.contains("catalog-service answered 400")))
                .singleElement().satisfies(line -> assertThat(line).contains(" ERROR ")
                        .contains("which it never sends for a valid call"));

        services.answer(FakeServices.PRODUCTS, 502, "{}");
        assertThat(unavailableCode(() -> catalog.products(List.of(10L)))).isEqualTo("CATALOG_UNAVAILABLE");
        assertThat(output).contains("WARN").contains("catalog-service answered 502");
    }

    /** The rate keeps scale 2. Mutation: the rate's scale left as sent. */
    @Test
    void merchantServiceAnswersStatusAndRate() {
        services.answer(FakeServices.MERCHANTS, 200,
                "{\"content\":[{\"id\":3,\"active\":true,\"commissionRate\":5},{\"id\":4,\"active\":false,"
                        + "\"commissionRate\":12.50}]}");

        Map<Long, MerchantStatus> merchants = merchantClient.merchants(List.of(3L, 4L, 5L));

        assertThat(merchants).containsOnlyKeys(3L, 4L);
        assertThat(merchants.get(3L).commissionRate()).isEqualTo(new BigDecimal("5.00"));
        assertThat(merchants.get(4L)).isEqualTo(new MerchantStatus(4, false, new BigDecimal("12.50")));
    }

    /** A rate outside 0.00 to 100.00 or with 3 decimals is unreadable. Mutation: the range check removed. */
    @ParameterizedTest
    @ValueSource(strings = {"100.01", "-0.01", "5.001"})
    void aRateMerchantServiceNeverGivesIsUnreadable(String rate) {
        services.answer(FakeServices.MERCHANTS, 200,
                "{\"content\":[{\"id\":3,\"active\":true,\"commissionRate\":" + rate + "}]}");

        assertThat(unavailableCode(() -> merchantClient.merchants(List.of(3L)))).isEqualTo("MERCHANT_UNAVAILABLE");
    }

    /** Mutation: the key header not sent (the fake answers 401). */
    @Test
    void bothInternalCallsSendTheKey() {
        services.givenMerchant(3, true, "5.00");
        services.givenProduct(10, 3, "Tea", "2.50", true, true, "CREATED");

        assertThat(catalog.products(List.of(10L))).hasSize(1);
        assertThat(merchantClient.merchants(List.of(3L))).hasSize(1);
        assertThat(services.calls()).extracting(FakeServices.Call::apiKey).containsOnly(API_KEY);
    }
}
