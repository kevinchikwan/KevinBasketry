package com.basketry.merchant.merchant;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Body of GET /merchants. Same shape as identity-service's GET /accounts,
 * rather than Spring's {@code Page} JSON, whose serialized form is not a
 * stable contract.
 */
public record MerchantPage(
        List<MerchantResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static MerchantPage from(Page<Merchant> page) {
        return new MerchantPage(
                page.getContent().stream().map(MerchantResponse::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
