package com.basketry.order.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.basketry.order.support.OrderServiceTestBase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Only in postgresTest: the reads and the timer's due lists use an index, not
 * a sequential scan, on tables of 20,000 orders where only a few dozen are due
 * (V2__add_read_and_timer_indexes.sql). The SQL here is what the repository
 * queries compile to, written out so EXPLAIN can run it with literal values.
 * The plans are printed for the review. One query cannot use an index and is
 * not asserted: Admin's unfiltered count reads every row, because it counts
 * every row.
 */
@Tag("postgres")
class QueryPlanTests extends OrderServiceTestBase {

    private static final int ORDERS = 20_000;
    private static final long FIRST_ID = 1_000_000;

    private void manyOrders() {
        // 1 in 250 PENDING past its deadline, 1 in 250 FAILING with a refund and a release due, the rest SUCCEEDED.
        jdbc.update("""
                INSERT INTO customer_order (id, order_reference, buyer_account_id, idempotency_key, request_fingerprint,
                    status, needs_attention, total_amount, deadline_at, created_at, decided_at, completed_at)
                SELECT g, 'ORD-' || lpad(g::text, 20, '0'), g % 2000 + 1, 'key-' || g, repeat('0', 64),
                    CASE g % 250 WHEN 0 THEN 'PENDING' WHEN 1 THEN 'FAILING' ELSE 'SUCCEEDED' END,
                    g % 500 = 1, 10.00, now() - interval '1 hour', now() - interval '2 hours',
                    CASE g % 250 WHEN 0 THEN NULL ELSE now() END,
                    CASE g % 250 WHEN 0 THEN NULL WHEN 1 THEN NULL ELSE now() END
                FROM generate_series(?, ?) g""", FIRST_ID, FIRST_ID + ORDERS - 1);
        jdbc.update("""
                INSERT INTO merchant_charge (order_id, merchant_id, subtotal, discount, amount, commission_rate, commission,
                    status, transaction_ref, resends, compensation_due_at)
                SELECT g, g % 50 + 1, 10.00, 0.00, 10.00, 5.00, 0.50,
                    CASE g % 250 WHEN 0 THEN 'REQUESTED' WHEN 1 THEN 'REFUND_REQUESTED' ELSE 'CHARGED' END,
                    'TXN-' || lpad(to_hex(g), 32, '0'), 0,
                    CASE g % 250 WHEN 1 THEN now() - interval '1 minute' END
                FROM generate_series(?, ?) g""", FIRST_ID, FIRST_ID + ORDERS - 1);
        jdbc.update("""
                INSERT INTO order_line (order_id, product_id, merchant_id, product_name, unit_price, quantity, line_amount,
                    status, resends, compensation_due_at)
                SELECT g, g, g % 50 + 1, 'Product', 10.00, 1, 10.00,
                    CASE g % 250 WHEN 0 THEN 'REQUESTED' WHEN 1 THEN 'RELEASE_REQUESTED' ELSE 'RESERVED' END, 0,
                    CASE g % 250 WHEN 1 THEN now() - interval '1 minute' END
                FROM generate_series(?, ?) g""", FIRST_ID, FIRST_ID + ORDERS - 1);
        jdbc.execute("ANALYZE customer_order");
        jdbc.execute("ANALYZE merchant_charge");
        jdbc.execute("ANALYZE order_line");
    }

    private String plan(String sql) {
        return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
    }

    /**
     * Mutations: V2 without ix_customer_order_status_deadline,
     * ix_merchant_charge_status_due or ix_order_line_status_due (the timer's
     * three lists); without ix_customer_order_buyer_id,
     * ix_order_line_merchant_order or ix_customer_order_attention_id (the
     * reads). Without its index a query plans a Seq Scan or walks the whole
     * primary key with a filter, which is as bad; so each query must use the
     * index named for it. The buyer's filter alone also had
     * uq_customer_order_buyer_key before V2; the new index gives the page in id
     * order without a sort.
     */
    @Test
    void noReadAndNoTimerQueryScansAWholeTable() {
        manyOrders();
        String some = String.join(",", List.of(Long.toString(FIRST_ID), Long.toString(FIRST_ID + 1),
                Long.toString(FIRST_ID + 2)));
        Map<String, String> queries = new LinkedHashMap<>();
        // The index each query must use; a query not named here must at least not scan a whole table.
        Map<String, String> expected = new LinkedHashMap<>();
        queries.put("timer: past the deadline", """
                SELECT o.id FROM customer_order o WHERE o.status IN ('PENDING', 'FAILING') AND o.deadline_at <= now()
                AND (EXISTS (SELECT 1 FROM merchant_charge c WHERE c.order_id = o.id AND c.status = 'REQUESTED')
                  OR EXISTS (SELECT 1 FROM order_line l WHERE l.order_id = o.id AND l.status = 'REQUESTED'))
                ORDER BY o.id LIMIT 100""");
        queries.put("timer: refunds due (and STUCK)", """
                SELECT DISTINCT c.order_id FROM merchant_charge c WHERE c.status = 'REFUND_REQUESTED'
                AND c.compensation_due_at <= now() ORDER BY c.order_id LIMIT 100""");
        queries.put("timer: releases due (and STUCK)", """
                SELECT DISTINCT l.order_id FROM order_line l WHERE l.status IN ('RELEASE_REQUESTED', 'TIMED_OUT')
                AND l.compensation_due_at <= now() ORDER BY l.order_id LIMIT 100""");
        queries.put("read: a buyer's page", """
                SELECT * FROM customer_order WHERE buyer_account_id = 12 ORDER BY id DESC LIMIT 20""");
        queries.put("read: a buyer's count", "SELECT count(*) FROM customer_order WHERE buyer_account_id = 12");
        queries.put("read: a merchant's page", """
                SELECT o.* FROM customer_order o WHERE EXISTS (SELECT 1 FROM order_line l WHERE l.order_id = o.id
                AND l.merchant_id = 3) ORDER BY o.id DESC LIMIT 20""");
        queries.put("read: needs attention", """
                SELECT * FROM customer_order WHERE needs_attention ORDER BY id DESC LIMIT 20""");
        queries.put("read: needs attention count", "SELECT count(*) FROM customer_order WHERE needs_attention");
        queries.put("read: every order's page", "SELECT * FROM customer_order ORDER BY id DESC LIMIT 20");
        queries.put("read: one order", "SELECT * FROM customer_order WHERE order_reference = 'ORD-00000000000001000007'");
        queries.put("read: its charge legs", "SELECT * FROM merchant_charge WHERE order_id = 1000007 ORDER BY merchant_id");
        queries.put("read: its lines", "SELECT * FROM order_line WHERE order_id = 1000007 ORDER BY product_id");
        queries.put("read: line counts of a page", "SELECT order_id, count(*) FROM order_line WHERE order_id IN ("
                + some + ") GROUP BY order_id");
        queries.put("read: a merchant's own legs of a page", "SELECT * FROM merchant_charge WHERE order_id IN ("
                + some + ") AND merchant_id = 3");

        expected.put("timer: past the deadline", "ix_customer_order_status_deadline");
        expected.put("timer: refunds due (and STUCK)", "ix_merchant_charge_status_due");
        expected.put("timer: releases due (and STUCK)", "ix_order_line_status_due");
        expected.put("read: a buyer's page", "ix_customer_order_buyer_id");
        expected.put("read: a merchant's page", "ix_order_line_merchant_order");
        expected.put("read: needs attention", "ix_customer_order_attention_id");
        expected.put("read: every order's page", "customer_order_pkey");
        expected.put("read: one order", "uq_customer_order_reference");
        expected.put("read: its charge legs", "pk_merchant_charge");
        expected.put("read: its lines", "pk_order_line");

        queries.forEach((name, sql) -> {
            String plan = plan(sql);
            System.out.println("=== PLAN " + name + "\n" + plan);
            assertThat(plan).as(name).doesNotContain("Seq Scan");
            if (expected.containsKey(name)) {
                assertThat(plan).as(name).contains(" " + expected.get(name) + " ");
            }
        });
        System.out.println("=== PLAN read: every order's count (not asserted)\n"
                + plan("SELECT count(*) FROM customer_order"));
    }
}
