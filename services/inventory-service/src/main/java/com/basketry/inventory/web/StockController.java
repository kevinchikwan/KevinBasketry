package com.basketry.inventory.web;

import com.basketry.inventory.auth.TokenCapabilities;
import com.basketry.inventory.stock.AdjustCommand;
import com.basketry.inventory.stock.StockLevelRepository;
import com.basketry.inventory.stock.StockService;
import com.basketry.inventory.stock.UnknownProductException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.OptionalLong;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The stock endpoints for the Merchant portal and Admin (FR-M4; DESIGN.md
 * Section 3, inventory-service). Every one needs a bearer JWT. As in
 * merchant-service, request validation runs before the capability check: an
 * invalid request is a 400 for anyone, never a 403. Nothing is changed or
 * revealed either way.
 */
@RestController
public class StockController {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final StockLevelRepository stockLevels;
    private final StockService stock;

    public StockController(StockLevelRepository stockLevels, StockService stock) {
        this.stockLevels = stockLevels;
        this.stock = stock;
    }

    /**
     * Admin: every product, or one merchant's with {@code merchantId}. A
     * merchant: its own products only; a {@code merchantId} other than its own
     * is a 403. Paging as GET /accounts, in product order.
     */
    @GetMapping("/stock")
    @Transactional(readOnly = true)
    public StockPage list(@RequestParam(defaultValue = "0") @Min(0) int page,
                          @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size,
                          @RequestParam(required = false) @Min(1) Long merchantId,
                          @AuthenticationPrincipal Jwt jwt) {
        PageRequest request = PageRequest.of(page, size, Sort.by("productId").ascending());
        if (TokenCapabilities.isAdmin(jwt)) {
            return StockPage.from(merchantId == null
                    ? stockLevels.findAll(request)
                    : stockLevels.findByMerchantId(merchantId, request));
        }
        OptionalLong own = TokenCapabilities.merchantId(jwt);
        if (own.isEmpty() || (merchantId != null && merchantId != own.getAsLong())) {
            throw StockAccessDeniedException.view();
        }
        return StockPage.from(stockLevels.findByMerchantId(own.getAsLong(), request));
    }

    /**
     * Admin, or the merchant that owns the product. Ownership is known only
     * after the lookup, so a non-admin gets 403 both for another merchant's
     * product and for one that does not exist; only an admin gets a 404.
     */
    @GetMapping("/stock/{productId}")
    @Transactional(readOnly = true)
    public StockResponse get(@PathVariable @Min(1) long productId, @AuthenticationPrincipal Jwt jwt) {
        if (TokenCapabilities.isAdmin(jwt)) {
            return stockLevels.findById(productId).map(StockResponse::from)
                    .orElseThrow(() -> new UnknownProductException(productId));
        }
        return stockLevels.findById(productId)
                .filter(level -> TokenCapabilities.isMerchant(jwt, level.getMerchantId()))
                .map(StockResponse::from)
                .orElseThrow(StockAccessDeniedException::view);
    }

    /**
     * The owning merchant only (FR-M4); Admin can view but not adjust. A delta,
     * applied in one conditional statement (DESIGN.md Section 2). 200 with the
     * stock after the change; 409 STOCK_WOULD_GO_NEGATIVE or STOCK_LIMIT_EXCEEDED.
     */
    @PostMapping("/stock/{productId}/adjustments")
    public StockResponse adjust(@PathVariable @Min(1) long productId,
                                @Valid @RequestBody AdjustStockRequest request,
                                @AuthenticationPrincipal Jwt jwt) {
        request.rejectUnknownFields();
        AdjustCommand command = request.toCommand(productId);
        // The owner never changes, so this check cannot go stale before the adjustment.
        boolean owner = stockLevels.findById(productId)
                .filter(level -> TokenCapabilities.isMerchant(jwt, level.getMerchantId()))
                .isPresent();
        if (!owner) {
            throw StockAccessDeniedException.adjust();
        }
        return StockResponse.from(stock.adjust(command));
    }
}
