package com.basketry.identity.merchantstatus;

import static com.basketry.identity.merchantstatus.StatusEvents.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.basketry.identity.account.Account;
import com.basketry.identity.account.AccountRegistrationService;
import com.basketry.identity.account.AccountRepository;
import com.basketry.identity.merchantlink.MerchantLinkService;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

/**
 * The normal first run: merchant-service creates the topic on the first status
 * change, so identity-service usually starts before it exists. The listener
 * must find the topic by itself and read from its beginning.
 */
@EmbeddedKafka(
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@SpringBootTest(properties = {
        "basketry.merchant-status.consumer.enabled=true",
        "spring.kafka.consumer.properties[metadata.max.age.ms]=500"})
class TopicCreatedAfterStartupTests {

    private static final long MERCHANT = 501;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountRegistrationService registrationService;

    @Autowired
    private MerchantLinkService linkService;

    @Test
    void theListenerFindsTheTopicAndReadsItFromTheBeginning() throws Exception {
        registrationService.registerBuyer("late@example.com", "Corr3ct-Horse!");
        linkService.link("late@example.com", MERCHANT, null);
        MessageListenerContainer container = listeners.getListenerContainers().iterator().next();
        assertThat(container.isRunning()).isTrue();

        broker.addTopics(new NewTopic("merchant.status.changed", 3, (short) 1));
        kafka.send("merchant.status.changed", Long.toString(MERCHANT), event(MERCHANT, false, 1)).get();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Account account = accounts.findByMerchantId(MERCHANT).orElseThrow();
            assertThat(account.getMerchantStatusVersion()).isEqualTo(1);
            assertThat(account.isMerchantActive()).isFalse();
        });
    }
}
