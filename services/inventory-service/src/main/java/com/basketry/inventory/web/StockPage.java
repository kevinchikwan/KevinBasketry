package com.basketry.inventory.web;

import com.basketry.inventory.stock.StockLevel;
import java.util.List;
import org.springframework.data.domain.Page;

/** Body of GET /stock. The same shape as identity-service's GET /accounts and merchant-service's GET /merchants. */
public record StockPage(List<StockResponse> content, int page, int size, long totalElements, int totalPages) {

    static StockPage from(Page<StockLevel> page) {
        return new StockPage(page.getContent().stream().map(StockResponse::from).toList(), page.getNumber(),
                page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
