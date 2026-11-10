package com.basketry.catalog.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.catalog.support.CatalogServiceTestBase;
import com.basketry.catalog.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.test.json.JsonCompareMode;

/**
 * Row 11 of the Day 12 review: health only, with no details, as
 * inventory-service. Neither Redis nor inventory-service is part of it: in the
 * default run the Redis client points at a port where nothing listens, and
 * health and readiness are still UP.
 */
class ActuatorTests extends CatalogServiceTestBase {

    /** Without a token, as probes call it. Mutation: show-details always (a "components" member would fail it). */
    @Test
    void healthIsUpAndShowsNoDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}",
                        JsonCompareMode.STRICT));
    }

    /** Mutation: Redis health back on (readiness DOWN without Redis, and the pod unready). */
    @Test
    void readinessIsUpWithoutRedisOrInventory() throws Exception {
        inventory.answerReadsWith(503);

        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT));
    }

    /** With an admin token, so the 404 is the endpoint's absence. Mutation: expose every endpoint. */
    @Test
    void noOtherActuatorEndpointIsExposed() throws Exception {
        for (String endpoint : new String[] {"env", "heapdump", "configprops", "beans", "loggers", "mappings"}) {
            mvc.perform(bearer(get("/actuator/" + endpoint), TestTokens.admin())).andExpect(status().isNotFound());
        }
    }
}
