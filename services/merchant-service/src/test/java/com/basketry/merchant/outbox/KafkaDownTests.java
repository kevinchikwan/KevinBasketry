package com.basketry.merchant.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.basketry.merchant.merchant.Merchant;
import com.basketry.merchant.support.MerchantServiceTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The real publisher with nothing listening on the broker's port. The service
 * starts, the PATCH succeeds, and the event waits in the outbox.
 */
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.admin.operation-timeout=1s",
        "spring.kafka.admin.properties[request.timeout.ms]=500",
        "spring.kafka.admin.properties[default.api.timeout.ms]=500"})
class KafkaDownTests extends MerchantServiceTestBase {

    @Autowired
    private OutboxRelay relay;

    @Test
    void thePatchSucceedsAndTheEventWaits() throws Exception {
        Merchant merchant = saveLinked("shop@example.com");

        patchAsAdmin(merchant, "{\"active\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        assertThat(relay.publishPending()).isFalse();

        assertThat(outbox.findByMerchantIdOrderByIdAsc(merchant.getId())).singleElement().satisfies(event -> {
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(event.getAttempts()).isEqualTo(1);
            assertThat(event.getLastError()).contains("Kafka is not reachable");
        });
    }
}
