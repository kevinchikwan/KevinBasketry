package com.basketry.identity.merchantstatus;

import static com.basketry.identity.merchantstatus.StatusEvents.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.basketry.identity.account.Account;
import com.basketry.identity.account.AccountRegistrationService;
import com.basketry.identity.account.AccountRepository;
import com.basketry.identity.merchantlink.MerchantLinkService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;

/**
 * Events published while the listener was not running, with no offset committed
 * for its group yet, are still read: auto.offset.reset is earliest.
 */
@EmbeddedKafka(
        topics = "merchant.status.changed",
        partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@SpringBootTest(properties = {
        "basketry.merchant-status.consumer.enabled=true",
        "spring.kafka.listener.auto-startup=false"})
class EventsBeforeStartupTests {

    private static final long MERCHANT = 601;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountRegistrationService registrationService;

    @Autowired
    private MerchantLinkService linkService;

    @Test
    void theyAreAppliedOnceTheListenerStarts() throws Exception {
        registrationService.registerBuyer("early@example.com", "Corr3ct-Horse!");
        linkService.link("early@example.com", MERCHANT, null);
        kafka.send("merchant.status.changed", Long.toString(MERCHANT), event(MERCHANT, false, 1)).get();
        kafka.send("merchant.status.changed", Long.toString(MERCHANT), event(MERCHANT, true, 2)).get();
        kafka.send("merchant.status.changed", Long.toString(MERCHANT), event(MERCHANT, false, 3)).get();

        listeners.getListenerContainers().forEach(MessageListenerContainer::start);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Account account = accounts.findByMerchantId(MERCHANT).orElseThrow();
            assertThat(account.getMerchantStatusVersion()).isEqualTo(3);
            assertThat(account.isMerchantActive()).isFalse();
        });
    }
}
