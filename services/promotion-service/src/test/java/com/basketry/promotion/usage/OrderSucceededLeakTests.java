package com.basketry.promotion.usage;

import static com.basketry.promotion.support.TopicReader.PATIENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.basketry.promotion.coupon.Coupon;
import com.basketry.promotion.coupon.CouponCode;
import com.basketry.promotion.coupon.CouponRepository;
import com.basketry.promotion.coupon.CouponScope;
import com.basketry.promotion.coupon.Discount;
import com.basketry.promotion.coupon.DiscountType;
import com.basketry.promotion.support.PromotionServiceTestBase;
import com.basketry.promotion.support.TopicReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.node.ObjectNode;

/**
 * The listener's log lines and dead-letter headers, with Spring Kafka at DEBUG
 * and our own loggers at DEBUG (DESIGN.md Section 5, promotion-service). A
 * sentinel sent in a malformed event (a wrong-typed value, a value breaking a
 * rule, an unknown field's name and value, not JSON, not UTF-8), in a record
 * key, or as the code of a coupon that does not exist, reaches no log line and
 * no dead-letter header; nor does the code of a counted coupon (pricing's rule,
 * kept here). The dead letter's value keeps the sentinel by design: it is the
 * record, byte for byte. Never run org.springframework.kafka or
 * org.apache.kafka.clients at TRACE: they print whole records. Its own context
 * (the last property), so its output capture is not shared.
 */
@ExtendWith(OutputCaptureExtension.class)
@EmbeddedKafka(
        topics = OrderSucceededTopic.NAME,
        partitions = 3,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        brokerProperties = "auto.create.topics.enable=false")
@TestPropertySource(properties = {
        "basketry.coupon-usage.consumer.enabled=true",
        "logging.level.org.springframework.kafka=DEBUG",
        "logging.level.com.basketry=DEBUG",
        "basketry.test.context=order-succeeded-leak"})
class OrderSucceededLeakTests extends PromotionServiceTestBase {

    private static final String SENTINEL = OrderSucceededParserTests.SENTINEL;
    /** The sentinel as a normalised code: of a coupon that does not exist, and of one that does. */
    private static final String MISSING_CODE = SENTINEL.toUpperCase(Locale.ROOT);
    private static final String COUNTED_CODE = "KEPT" + SENTINEL.substring(0, 10).toUpperCase(Locale.ROOT);

    @Autowired
    private KafkaTemplate<byte[], byte[]> kafka;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private CouponRepository coupons;

    private static String event(long orderId, String code) {
        return OrderSucceededKafkaTests.event(orderId, code);
    }

    private void send(String key, byte[] value) throws Exception {
        kafka.send(OrderSucceededTopic.NAME, key.getBytes(StandardCharsets.UTF_8), value).get();
    }

    private void send(String key, String value) throws Exception {
        send(key, value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Mutations: the parser's "not valid JSON" message with Jackson's message
     * appended (it quotes the input); the WARN for a missing coupon with the
     * code; the DEBUG line for a counted event with the code; the recoverer's
     * log line with the value as text. (Logging the record itself would not
     * leak: its key and value are byte arrays, printed as [B@ and a hash.)
     */
    @Test
    void noSentinelReachesALogLineOrADeadLetterHeader(CapturedOutput output) throws Exception {
        coupons.save(new Coupon(new CouponCode(COUNTED_CODE), CouponScope.PLATFORM, null,
                new Discount(DiscountType.FIXED, new BigDecimal("5.00")), CouponUsageServiceTests.FAR_FUTURE));
        List<String> keys = new ArrayList<>();

        String[] malformed = {
                SENTINEL,
                "{\"schemaVersion\":1,\"" + SENTINEL + "\":}",
                event(301, "SPRING10").replace("\"orderId\":301", "\"orderId\":\"" + SENTINEL + "\""),
                event(302, "SPRING10").replace("\"orderId\":302", "\"orderId\":" + SENTINEL),
                event(303, "SPRING10").replace("\"SPRING10\"", "\"" + SENTINEL + "\""),
                event(304, "SPRING10").replace("\"eventId\":", "\"eventId\":\"" + SENTINEL + "\",\"x\":"),
                event(305, "SPRING10").replace("\"occurredAt\":\"2026-11-13T10:00:00Z\"",
                        "\"occurredAt\":\"" + SENTINEL + "\""),
                OrderSucceededParserTests.goldenWith(e -> ((ObjectNode) e.get("merchants").get(0))
                        .put("discount", SENTINEL)),
                event(306, "SPRING10") + SENTINEL,
                // An unknown field is ignored, so a broken one makes the event malformed.
                event(307, "SPRING10").replace("{\"schemaVersion\":1,",
                        "{\"schemaVersion\":1,\"" + SENTINEL + "\":\"" + SENTINEL + "\",\"orderId\":0,")};
        for (int i = 0; i < malformed.length; i++) {
            String key = "leak-" + i;
            keys.add(key);
            send(key, malformed[i]);
        }
        byte[] notUtf8 = (SENTINEL + (char) 0xE9).getBytes(StandardCharsets.UTF_8);
        notUtf8[notUtf8.length - 1] = (byte) 0x28;   // \xC3 followed by "(": not UTF-8
        keys.add("leak-utf8");
        send("leak-utf8", notUtf8);
        keys.add("leak-key-" + SENTINEL);
        send("leak-key-" + SENTINEL, event(308, "SPRING10").replace("\"schemaVersion\":1", "\"schemaVersion\":2"));

        send("leak-missing", event(309, MISSING_CODE));
        send("leak-counted", event(310, COUNTED_CODE));

        // Every malformed record dead-lettered, and both good ones handled.
        for (String key : keys) {
            List<ConsumerRecord<byte[], byte[]>> deadLetters = TopicReader.readKey(broker,
                    OrderSucceededKafkaTests.DLT, key.getBytes(StandardCharsets.UTF_8), 1);
            for (Header header : deadLetters.getFirst().headers()) {
                assertThat(new String(header.value(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT))
                        .as(key + " " + header.key()).doesNotContain(SENTINEL.toLowerCase(Locale.ROOT));
            }
        }
        await().atMost(PATIENCE).until(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM coupon_usage WHERE order_id = 310", Long.class) == 1);
        await().atMost(PATIENCE).until(() -> output.getAll().contains("orderId 309"));

        List<String> leaks = output.getAll().lines()
                .filter(line -> line.toLowerCase(Locale.ROOT).contains(SENTINEL.toLowerCase(Locale.ROOT))
                        || line.contains(COUNTED_CODE))
                .toList();
        assertThat(leaks).isEmpty();
        assertThat(output.getAll()).contains("Sending record " + OrderSucceededTopic.NAME + "-");
    }
}
