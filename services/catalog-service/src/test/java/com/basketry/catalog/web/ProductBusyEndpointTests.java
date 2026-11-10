package com.basketry.catalog.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.catalog.product.ProductBusyException;
import com.basketry.catalog.product.ProductService;
import com.basketry.catalog.support.CatalogServiceTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The row-lock timeout reaches the client as 503 PRODUCT_BUSY, so it can retry.
 * The timeout itself is ProductLockTimeoutTests (postgresTest); here the
 * service is replaced, so the mapping is checked in the default run too.
 * Mutation: drop the handler (a 500) or its code.
 */
class ProductBusyEndpointTests extends CatalogServiceTestBase {

    @MockitoBean
    private ProductService busyService;

    @Test
    void aLockTimeoutIs503ProductBusy() throws Exception {
        when(busyService.update(anyLong(), anyLong(), any()))
                .thenThrow(new ProductBusyException(new RuntimeException("lock timeout")));

        perform(json(bearer(patch("/products/1"), ownerToken()), "{\"price\":3.00}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PRODUCT_BUSY"));
    }
}
