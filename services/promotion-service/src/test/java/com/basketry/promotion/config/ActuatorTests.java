package com.basketry.promotion.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.promotion.support.PromotionServiceTestBase;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Health is open, with no details, and nothing else of actuator is exposed or
 * reachable. Once a copy of payment-service's; with Spring Security (A6) any
 * other path is a bare 401 before actuator could answer 404, so the exposure
 * itself is read from application.yml.
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
    void onlyHealthIsExposed() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties application = yaml.getObject();

        assertThat(application.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
    }

    /** Mutation: {@code anyRequest().permitAll()} in the default chain (actuator's 404 instead). */
    @Test
    void noOtherActuatorEndpointIsReachable() throws Exception {
        for (String endpoint : new String[] {"env", "heapdump", "configprops", "beans", "loggers", "mappings"}) {
            mvc.perform(get("/actuator/" + endpoint)).andExpect(status().isUnauthorized());
        }
    }
}
