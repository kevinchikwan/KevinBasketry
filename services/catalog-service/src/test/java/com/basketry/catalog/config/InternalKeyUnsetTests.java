package com.basketry.catalog.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.catalog.support.TestCacheConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With no key configured (local dev), the service starts and refuses every
 * internal call, whatever is sent. Mutation: an unset key matching an empty or
 * any header.
 */
@SpringBootTest(properties = "basketry.internal.api-key=")
@AutoConfigureMockMvc
@Import(TestCacheConfig.class)
class InternalKeyUnsetTests {

    @Autowired
    private MockMvc mvc;

    @Test
    void everyInternalCallIs401() throws Exception {
        mvc.perform(get("/internal/products").param("ids", "42")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/products").param("ids", "42").header("X-Internal-Api-Key", ""))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/products").param("ids", "42")
                        .header("X-Internal-Api-Key", "test-only-internal-key-not-a-secret-000"))
                .andExpect(status().isUnauthorized());
    }
}
