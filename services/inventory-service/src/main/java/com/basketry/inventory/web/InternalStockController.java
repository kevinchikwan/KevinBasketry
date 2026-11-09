package com.basketry.inventory.web;

import com.basketry.inventory.stock.CreateOutcome;
import com.basketry.inventory.stock.StockLevelRepository;
import com.basketry.inventory.stock.StockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoints, for catalog-service (DESIGN.md Section 3,
 * inventory-service). Reached only with the internal API key (SecurityConfig),
 * never with a user JWT, and never routed from outside the cluster.
 */
@RestController
public class InternalStockController {

    static final int MAX_PRODUCT_IDS = 100;

    private final StockService stock;
    private final StockLevelRepository stockLevels;

    public InternalStockController(StockService stock, StockLevelRepository stockLevels) {
        this.stock = stock;
        this.stockLevels = stockLevels;
    }

    /**
     * Creates a product's stock row at 0 for its owner: 201 CREATED. The same
     * merchant again: 200 ALREADY_EXISTS, nothing changed. Another merchant:
     * 409 PRODUCT_OWNED_BY_OTHER_MERCHANT.
     */
    @PostMapping("/internal/stock")
    public ResponseEntity<CreateStockResponse> create(@Valid @RequestBody CreateStockRequest request) {
        request.rejectUnknownFields();
        CreateOutcome outcome = stock.create(request.toCommand());
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(CreateStockResponse.from(outcome));
    }

    /**
     * The stock of up to 100 products, for showing products out of stock
     * (FR-M6). Products with no stock row are left out; a repeated id is
     * answered once.
     */
    @GetMapping("/internal/stock")
    @Transactional(readOnly = true)
    public StockAvailability availability(
            @RequestParam @Size(min = 1, max = MAX_PRODUCT_IDS) List<@Min(1) Long> productIds) {
        return new StockAvailability(stockLevels.findByProductIdInOrderByProductIdAsc(new TreeSet<>(productIds))
                .stream()
                .map(level -> new StockAvailability.Item(level.getProductId(), level.getQuantity()))
                .toList());
    }
}
