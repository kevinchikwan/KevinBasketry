package com.basketry.catalog.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.catalog.support.CatalogServiceTestBase;
import org.junit.jupiter.api.Test;

/**
 * Row 11 of the Day 12 review: the configured origin (the Buyer and Merchant
 * apps) may browse and write from a browser; other origins may not; the
 * internal endpoints send no CORS headers at all.
 */
class CorsTests extends CatalogServiceTestBase {

    /** Mutation: CORS methods without PATCH (the Merchant portal could not edit a product). */
    @Test
    void theConfiguredOriginMayPatchAProductFromABrowser() throws Exception {
        perform(options("/products/1")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    /** Mutation: cors() left out of the public chain (the Buyer app could not browse). */
    @Test
    void theConfiguredOriginMayBrowse() throws Exception {
        perform(get("/products").header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void anotherOriginIsRefused() throws Exception {
        perform(options("/products")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        perform(get("/products").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
    }

    /** Mutation: CORS on the internal chain. */
    @Test
    void theInternalEndpointsSendNoCorsHeaders() throws Exception {
        perform(internal(get("/internal/products").param("ids", "1")).header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
