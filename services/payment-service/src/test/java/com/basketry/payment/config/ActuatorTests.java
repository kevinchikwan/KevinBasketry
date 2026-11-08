package com.basketry.payment.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.payment.support.PaymentServiceTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

/**
 * No REST API (DESIGN.md Section 3): the only endpoint is health, with no
 * details, and nothing else of actuator is reachable.
 */
@AutoConfigureMockMvc
class ActuatorTests extends PaymentServiceTestBase {

    @Autowired
    private MockMvc mvc;

    /** Strict: a "components" or "details" member (database, disk) would fail it. */
    @Test
    void healthIsUpAndShowsNoDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\",\"groups\":[\"liveness\",\"readiness\"]}",
                        JsonCompareMode.STRICT));
    }

    @Test
    void noOtherActuatorEndpointIsExposed() throws Exception {
        for (String endpoint : new String[] {"env", "heapdump", "configprops", "beans", "loggers", "mappings"}) {
            mvc.perform(get("/actuator/" + endpoint)).andExpect(status().isNotFound());
        }
    }
}
