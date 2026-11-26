package com.basketry.promotion.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.promotion.support.PromotionServiceTestBase;
import com.basketry.promotion.support.TestTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Two creates with the same code at the same time, on PostgreSQL (ROADMAP
 * A7): one 201 and one 409, never a 500. The unique constraint decides, not a
 * lookup before the insert, which both creates could pass.
 */
@Tag("postgres")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CouponCreateRaceTests extends PromotionServiceTestBase {

    private static final String ADMIN = TestTokens.admin();

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    private final HttpClient client = HttpClient.newHttpClient();

    private CompletableFuture<HttpResponse<String>> create(String code) {
        String expiresAt = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS).toString();
        String body = "{\"code\":\"" + code + "\",\"scope\":\"PLATFORM\",\"discountType\":\"FIXED\","
                + "\"discountValue\":5.00,\"expiresAt\":\"" + expiresAt + "\"}";
        return client.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/coupons"))
                .header("Authorization", "Bearer " + ADMIN).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Made certain: another transaction has inserted the code and not yet
     * committed, so the create finds no coupon with it, and its insert waits
     * on the unique index; the other commits, and the create is a 409.
     * Mutation: a lookup before the insert, and the constraint's violation not
     * turned into the 409.
     */
    @Test
    void aCreateThatLosesToAnUncommittedInsertIs409() throws Exception {
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement insert = other.prepareStatement("INSERT INTO coupon (id, code, scope, merchant_id,"
                    + " discount_type, discount_value, expires_at, active) VALUES (nextval('coupon_id_seq'), 'RACE1',"
                    + " 'PLATFORM', NULL, 'FIXED', 5.00, ?, TRUE)")) {
                insert.setTimestamp(1, Timestamp.from(Instant.now().plus(Duration.ofDays(30))));
                insert.executeUpdate();
            }
            CompletableFuture<HttpResponse<String>> create = create("RACE1");
            waitForALockWait();
            other.commit();

            HttpResponse<String> answer = create.get(30, TimeUnit.SECONDS);
            assertThat(answer.statusCode()).as(answer.body()).isEqualTo(409);
            assertThat(answer.body()).contains("\"code\":\"COUPON_CODE_TAKEN\"");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE code = 'RACE1'", Long.class)).isOne();
    }

    /**
     * Pairs of creates sent together, 20 times: each pair is one 201 and one
     * 409. Mutation: the unique code's violation not turned into the 409.
     */
    @Test
    void twoCreatesWithTheSameCodeAreOne201AndOne409() throws Exception {
        for (int round = 0; round < 20; round++) {
            String code = "RACE" + (100 + round);
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<HttpResponse<String>>> pair = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                pair.add(CompletableFuture.runAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).thenCompose(ignored -> create(code)));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (CompletableFuture<HttpResponse<String>> future : pair) {
                HttpResponse<String> answer = future.get(30, TimeUnit.SECONDS);
                assertThat(answer.statusCode()).as(answer.body()).isIn(201, 409);
                statuses.add(answer.statusCode());
            }
            assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(201, 409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE code = ?", Long.class, code)).isOne();
        }
    }

    /** Until a statement of this database waits on a lock (the create's insert), at most 10 seconds. */
    private void waitForALockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database()"
                    + " AND wait_event_type = 'Lock'", Long.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("The create never waited on the uncommitted insert");
    }
}
