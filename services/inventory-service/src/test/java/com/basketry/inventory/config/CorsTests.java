package com.basketry.inventory.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.inventory.support.InventoryServiceTestBase;
import org.junit.jupiter.api.Test;

/** The Merchant portal's origin may call the stock endpoints from a browser; other origins may not. */
class CorsTests extends InventoryServiceTestBase {

    @Test
    void theConfiguredOriginMayAdjustStockFromABrowser() throws Exception {
        perform(options("/stock/42/adjustments")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void anotherOriginIsRefused() throws Exception {
        perform(options("/stock")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
