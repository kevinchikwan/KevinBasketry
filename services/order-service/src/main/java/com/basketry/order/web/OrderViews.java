package com.basketry.order.web;

import com.basketry.order.order.ChargeStatus;
import com.basketry.order.order.CustomerOrder;
import com.basketry.order.order.LineStatus;
import com.basketry.order.order.MerchantCharge;
import com.basketry.order.order.OrderLine;
import com.basketry.order.order.OrderStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What each reader sees of an order (DESIGN.md Section 3, order-service). Every
 * money value is a JSON number at scale 2.
 *
 * <ul>
 *   <li>The buyer: the order and every leg, without commission,
 *       transactionRef or needsAttention. Leg states are shown as the buyer
 *       should read them: STUCK as the compensation still requested
 *       (REFUND_REQUESTED, RELEASE_REQUESTED), TIMED_OUT as FAILED with its
 *       reason NO_RESULT. Our repair queue is not the buyer's business.</li>
 *   <li>A merchant: its own charge leg and lines only, raw, and the
 *       buyerAccountId (never an email); not the order total.</li>
 *   <li>Admin: everything, raw, with the resends and due times a repair
 *       needs.</li>
 * </ul>
 */
public final class OrderViews {

    private OrderViews() {
    }

    public record BuyerOrder(String reference, OrderStatus status, BigDecimal totalAmount, String couponCode,
                             Instant createdAt, List<BuyerCharge> merchants, List<BuyerLine> lines) {
    }

    public record BuyerCharge(long merchantId, BigDecimal amount, String status, String reasonCode) {
    }

    public record BuyerLine(long productId, long merchantId, String productName, BigDecimal unitPrice, int quantity,
                            BigDecimal lineAmount, String status, String reasonCode) {
    }

    public record MerchantOrder(String reference, OrderStatus status, long buyerAccountId, Instant createdAt,
                                MerchantChargeView charge, List<MerchantLine> lines) {
    }

    /** Null in a MerchantOrder when the merchant had nothing to pay (no charge leg). */
    public record MerchantChargeView(BigDecimal subtotal, BigDecimal discount, BigDecimal amount,
                                     BigDecimal commissionRate, BigDecimal commission, ChargeStatus status,
                                     String reasonCode, String transactionRef) {
    }

    public record MerchantLine(long productId, String productName, BigDecimal unitPrice, int quantity,
                               BigDecimal lineAmount, LineStatus status, String reasonCode) {
    }

    public record AdminOrder(String reference, OrderStatus status, boolean needsAttention, long buyerAccountId,
                             BigDecimal totalAmount, String couponCode, Instant createdAt, Instant deadlineAt,
                             Instant decidedAt, Instant completedAt, List<AdminCharge> merchants,
                             List<AdminLine> lines) {
    }

    public record AdminCharge(long merchantId, BigDecimal subtotal, BigDecimal discount, BigDecimal amount,
                              BigDecimal commissionRate, BigDecimal commission, ChargeStatus status,
                              String reasonCode, String transactionRef, int resends, Instant compensationDueAt) {
    }

    public record AdminLine(long productId, long merchantId, String productName, BigDecimal unitPrice, int quantity,
                            BigDecimal lineAmount, LineStatus status, String reasonCode, int resends,
                            Instant compensationDueAt) {
    }

    /**
     * One order in a list: the detail is at GET /orders/{reference}. For a
     * merchant, {@code totalAmount} is its own amount and {@code lineCount} its
     * own lines; {@code needsAttention} only for Admin.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderSummary(String reference, OrderStatus status, BigDecimal totalAmount, Instant createdAt,
                               long lineCount, Boolean needsAttention) {
    }

    public record SummaryPage(List<OrderSummary> content, int page, int size, long totalElements, int totalPages) {
    }

    static BuyerOrder buyer(CustomerOrder order, List<MerchantCharge> charges, List<OrderLine> lines) {
        return new BuyerOrder(order.getOrderReference(), order.getStatus(), order.getTotalAmount(),
                order.getCouponCode(), order.getCreatedAt(),
                charges.stream().map(charge -> new BuyerCharge(charge.getMerchantId(), charge.getAmount(),
                        buyerStatus(charge.getStatus()), charge.getReasonCode())).toList(),
                lines.stream().map(line -> new BuyerLine(line.getProductId(), line.getMerchantId(),
                        line.getProductName(), line.getUnitPrice(), line.getQuantity(), line.getLineAmount(),
                        buyerStatus(line.getStatus()), line.getReasonCode())).toList());
    }

    /** STUCK is our repair queue: the buyer sees the refund still requested. TIMED_OUT failed the order. */
    static String buyerStatus(ChargeStatus status) {
        return switch (status) {
            case STUCK -> ChargeStatus.REFUND_REQUESTED.name();
            case TIMED_OUT -> "FAILED";
            default -> status.name();
        };
    }

    static String buyerStatus(LineStatus status) {
        return switch (status) {
            case STUCK -> LineStatus.RELEASE_REQUESTED.name();
            case TIMED_OUT -> LineStatus.FAILED.name();
            default -> status.name();
        };
    }

    static MerchantOrder merchant(CustomerOrder order, long merchantId, List<MerchantCharge> charges,
                                  List<OrderLine> lines) {
        MerchantChargeView charge = charges.stream()
                .filter(leg -> leg.getMerchantId() == merchantId)
                .findFirst()
                .map(leg -> new MerchantChargeView(leg.getSubtotal(), leg.getDiscount(), leg.getAmount(),
                        leg.getCommissionRate(), leg.getCommission(), leg.getStatus(), leg.getReasonCode(),
                        leg.getTransactionRef()))
                .orElse(null);
        return new MerchantOrder(order.getOrderReference(), order.getStatus(), order.getBuyerAccountId(),
                order.getCreatedAt(), charge,
                lines.stream().filter(line -> line.getMerchantId() == merchantId)
                        .map(line -> new MerchantLine(line.getProductId(), line.getProductName(), line.getUnitPrice(),
                                line.getQuantity(), line.getLineAmount(), line.getStatus(), line.getReasonCode()))
                        .toList());
    }

    static AdminOrder admin(CustomerOrder order, List<MerchantCharge> charges, List<OrderLine> lines) {
        return new AdminOrder(order.getOrderReference(), order.getStatus(), order.isNeedsAttention(),
                order.getBuyerAccountId(), order.getTotalAmount(), order.getCouponCode(), order.getCreatedAt(),
                order.getDeadlineAt(), order.getDecidedAt(), order.getCompletedAt(),
                charges.stream().map(charge -> new AdminCharge(charge.getMerchantId(), charge.getSubtotal(),
                        charge.getDiscount(), charge.getAmount(), charge.getCommissionRate(), charge.getCommission(),
                        charge.getStatus(), charge.getReasonCode(), charge.getTransactionRef(), charge.getResends(),
                        charge.getCompensationDueAt())).toList(),
                lines.stream().map(line -> new AdminLine(line.getProductId(), line.getMerchantId(),
                        line.getProductName(), line.getUnitPrice(), line.getQuantity(), line.getLineAmount(),
                        line.getStatus(), line.getReasonCode(), line.getResends(), line.getCompensationDueAt()))
                        .toList());
    }
}
