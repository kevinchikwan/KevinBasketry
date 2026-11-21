package com.basketry.promotion.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.promotion.support.PromotionServiceTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

/**
 * No endpoint yet: the only one is health, with no details, and nothing else
 * of actuator is reachable. A copy of payment-service's.
 */
@AutoConfigureMockMvc
class ActuatorTests extends PromotionServiceTestBase {

    @Autowired
    private MockMvc mvc;

    /**
     * Strict: a "components" or "details" member (database, disk) would fail it.
     * Mutation: {@code show-details: always}.
     */
    @Test
    void healthIsUpAndShowsNoDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}",
                        JsonCompareMode.STRICT));
    }

    /** Mutation: {@code exposure.include: "*"}. */
    @Test
    void noOtherActuatorEndpointIsExposed() throws Exception {
        for (String endpoint : new String[] {"env", "heapdump", "configprops", "beans", "loggers", "mappings"}) {
            mvc.perform(get("/actuator/" + endpoint)).andExpect(status().isNotFound());
        }
    }
}
