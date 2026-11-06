package com.basketry.identity.merchantstatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.identity.account.AccountRegistrationService;
import com.basketry.identity.account.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The real listener with nothing on the broker's port. The context starting at
 * all is half the test: startup does not wait for Kafka. The service then
 * serves requests while the listener keeps trying in the background.
 */
@SpringBootTest(properties = {
        "basketry.merchant-status.consumer.enabled=true",
        "spring.kafka.bootstrap-servers=localhost:1"})
@AutoConfigureMockMvc
class KafkaDownTests {

    private static final String PASSWORD = "Corr3ct-Horse!";

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRegistrationService registrationService;

    @Autowired
    private AccountRepository accounts;

    @Test
    void theServiceStartsAndServesRequests() throws Exception {
        assertThat(listeners.getListenerContainers()).singleElement()
                .satisfies(container -> assertThat(container.isRunning()).isTrue());
        accounts.deleteAll();
        registrationService.registerBuyer("buyer@example.com", PASSWORD);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"buyer@example.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }
}
