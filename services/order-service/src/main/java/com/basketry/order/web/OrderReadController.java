package com.basketry.order.web;

import com.basketry.order.auth.TokenCapabilities;
import com.basketry.order.order.CustomerOrder;
import com.basketry.order.order.CustomerOrderRepository;
import com.basketry.order.order.MerchantCharge;
import com.basketry.order.order.MerchantChargeRepository;
import com.basketry.order.order.OrderLine;
import com.basketry.order.order.OrderLineRepository;
import com.basketry.order.order.OrderReferences;
import com.basketry.order.web.OrderViews.OrderSummary;
import com.basketry.order.web.OrderViews.SummaryPage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /orders/{reference} and GET /orders (FR-B13, FR-M8, FR-M9, FR-A11;
 * DESIGN.md Section 3, order-service). Which orders a reader gets always comes
 * from the token, never from an id in a parameter: the buyer its own, a
 * merchant those with its lines (the token's merchantId claim: a stale window
 * of up to an hour is accepted for reads), Admin every one. Parameters are
 * checked before capabilities (400 before 403).
 */
@RestController
public class OrderReadController {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final CustomerOrderRepository orders;
    private final MerchantChargeRepository charges;
    private final OrderLineRepository lines;

    public OrderReadController(CustomerOrderRepository orders, MerchantChargeRepository charges,
                               OrderLineRepository lines) {
        this.orders = orders;
        this.charges = charges;
        this.lines = lines;
    }

    /**
     * Admin: the admin view, 404 only for a missing order. The order's buyer:
     * the buyer view. A merchant with a line in it: the merchant view. Anyone
     * else: 404, as for a missing order, so nothing is learned about other
     * orders.
     */
    @GetMapping("/orders/{reference}")
    @Transactional(readOnly = true)
    public Object get(@PathVariable String reference, @AuthenticationPrincipal Jwt jwt) {
        if (!OrderReferences.FORMAT.matcher(reference).matches()) {
            throw new OrderWebExceptions.BadRequest("A reference is ORD- and 20 characters of A-Z and 2-7.");
        }
        long accountId = TokenCapabilities.accountId(jwt);
        CustomerOrder order = orders.findByOrderReference(reference).orElseThrow(OrderWebExceptions.NotFound::new);
        List<MerchantCharge> orderCharges = charges.findByOrderIdOrderByMerchantIdAsc(order.getId());
        List<OrderLine> orderLines = lines.findByOrderIdOrderByProductIdAsc(order.getId());
        if (TokenCapabilities.isAdmin(jwt)) {
            return OrderViews.admin(order, orderCharges, orderLines);
        }
        if (order.getBuyerAccountId() == accountId) {
            return OrderViews.buyer(order, orderCharges, orderLines);
        }
        OptionalLong merchantId = TokenCapabilities.merchantId(jwt);
        if (merchantId.isPresent() && orderLines.stream().anyMatch(line -> line.getMerchantId() == merchantId.getAsLong())) {
            return OrderViews.merchant(order, merchantId.getAsLong(), orderCharges, orderLines);
        }
        throw new OrderWebExceptions.NotFound();
    }

    /**
     * Newest first. {@code view}: buyer (default) or merchant, for non-admins;
     * {@code needsAttention}: Admin only. Either from the wrong reader is a 403.
     */
    @GetMapping("/orders")
    @Transactional(readOnly = true)
    public SummaryPage list(@RequestParam(required = false) String page, @RequestParam(required = false) String size,
                            @RequestParam(required = false) String view,
                            @RequestParam(required = false) String needsAttention,
                            @AuthenticationPrincipal Jwt jwt) {
        int pageNumber = number(page, 0, 0, Integer.MAX_VALUE, "page must be a whole number from 0.");
        int pageSize = number(size, DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE, "size must be a whole number from 1 to 100.");
        if (view != null && !view.equals("buyer") && !view.equals("merchant")) {
            throw new OrderWebExceptions.BadRequest("view must be buyer or merchant.");
        }
        if (needsAttention != null && !needsAttention.equals("true") && !needsAttention.equals("false")) {
            throw new OrderWebExceptions.BadRequest("needsAttention must be true or false.");
        }
        long accountId = TokenCapabilities.accountId(jwt);
        PageRequest request = PageRequest.of(pageNumber, pageSize, Sort.by("id").descending());
        if (TokenCapabilities.isAdmin(jwt)) {
            if (view != null) {
                throw new OrderWebExceptions.Forbidden("view is for buyers and merchants; Admin reads every order.");
            }
            Page<CustomerOrder> found = "true".equals(needsAttention)
                    ? orders.findByNeedsAttentionTrue(request) : orders.findAll(request);
            return page(found, CustomerOrder::getTotalAmount, lineCounts(found, OptionalLong.empty()), true);
        }
        if (needsAttention != null) {
            throw new OrderWebExceptions.Forbidden("needsAttention is for Admin.");
        }
        if ("merchant".equals(view)) {
            long merchantId = TokenCapabilities.merchantId(jwt).orElseThrow(() ->
                    new OrderWebExceptions.Forbidden("view=merchant needs an active merchant account."));
            Page<CustomerOrder> found = orders.findWithLinesOf(merchantId, request);
            Map<Long, MerchantCharge> own = charges.findByOrderIdInAndMerchantId(ids(found), merchantId).stream()
                    .collect(Collectors.toMap(MerchantCharge::getOrderId, Function.identity()));
            return page(found, order -> own.containsKey(order.getId()) ? own.get(order.getId()).getAmount()
                    : java.math.BigDecimal.ZERO.setScale(2), lineCounts(found, OptionalLong.of(merchantId)), false);
        }
        Page<CustomerOrder> found = orders.findByBuyerAccountId(accountId, request);
        return page(found, CustomerOrder::getTotalAmount, lineCounts(found, OptionalLong.empty()), false);
    }

    private static int number(String value, int fallback, int min, int max, String rule) {
        if (value == null) {
            return fallback;
        }
        try {
            int number = Integer.parseInt(value);
            if (number >= min && number <= max) {
                return number;
            }
        } catch (NumberFormatException notANumber) {
            // Below.
        }
        throw new OrderWebExceptions.BadRequest(rule);
    }

    private static List<Long> ids(Page<CustomerOrder> found) {
        return found.getContent().stream().map(CustomerOrder::getId).toList();
    }

    private Map<Long, Long> lineCounts(Page<CustomerOrder> found, OptionalLong merchantId) {
        if (found.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = merchantId.isPresent()
                ? lines.countLinesByOrderOf(ids(found), merchantId.getAsLong())
                : lines.countLinesByOrder(ids(found));
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    private static SummaryPage page(Page<CustomerOrder> found, Function<CustomerOrder, java.math.BigDecimal> amount,
                                    Map<Long, Long> lineCounts, boolean admin) {
        List<OrderSummary> content = found.getContent().stream()
                .map(order -> new OrderSummary(order.getOrderReference(), order.getStatus(), amount.apply(order),
                        order.getCreatedAt(), lineCounts.getOrDefault(order.getId(), 0L),
                        admin ? order.isNeedsAttention() : null))
                .toList();
        return new SummaryPage(content, found.getNumber(), found.getSize(), found.getTotalElements(),
                found.getTotalPages());
    }
}
