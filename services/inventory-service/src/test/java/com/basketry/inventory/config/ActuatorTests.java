package com.basketry.inventory.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.inventory.support.InventoryServiceTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.json.JsonCompareMode;

/**
 * The only endpoint so far is health, with no details. Redis is not part of
 * it: in the default run the Redis client points at a port where nothing
 * listens, and health and readiness are still UP (DESIGN.md Section 2).
 */
class ActuatorTests extends InventoryServiceTestBase {

    /** Without a token, as Kubernetes probes call it. Strict: a "components" or "details" member would fail it. */
    @Test
    void healthIsUpAndShowsNoDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}",
                        JsonCompareMode.STRICT));
    }

    @Test
    void readinessIsUpWithoutRedis() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT));
    }

    /** With an admin token, so the 404 is the endpoint's absence, not a missing token. */
    @Test
    void noOtherActuatorEndpointIsExposed() throws Exception {
        for (String endpoint : new String[] {"env", "heapdump", "configprops", "beans", "loggers", "mappings"}) {
            mvc.perform(bearer(get("/actuator/" + endpoint), com.basketry.inventory.support.TestTokens.admin()))
                    .andExpect(status().isNotFound());
        }
    }
}
