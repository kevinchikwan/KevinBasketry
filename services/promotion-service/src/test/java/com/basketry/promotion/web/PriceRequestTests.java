package com.basketry.promotion.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The request object, outside Spring. */
class PriceRequestTests {

    /**
     * Neither the code nor a subtotal is in toString, should anything ever log
     * the object. Mutations: the record's own toString (the code and the
     * subtotals); MerchantLine's own toString (the subtotal).
     */
    @Test
    void toStringShowsNeitherTheCodeNorASubtotal() {
        PriceRequest request = new PriceRequest("LEAKCODE7Q",
                List.of(new PriceRequest.MerchantLine(3L, new BigDecimal("4242424.17"), Map.of())), Map.of());

        assertThat(request.toString()).isEqualTo("PriceRequest[code=***, merchants=1]")
                .doesNotContain("LEAKCODE7Q").doesNotContain("4242424");
        assertThat(request.merchants().getFirst().toString())
                .isEqualTo("MerchantLine[merchantId=3, subtotal=***]");
    }
}
