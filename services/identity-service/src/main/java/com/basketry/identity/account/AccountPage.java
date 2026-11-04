package com.basketry.identity.account;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Body of GET /accounts. Our own shape rather than Spring's {@code Page}
 * JSON, whose serialized form is not a stable contract.
 */
public record AccountPage(
        List<AccountResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static AccountPage from(Page<Account> page) {
        return new AccountPage(
                page.getContent().stream().map(AccountResponse::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
