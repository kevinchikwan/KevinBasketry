package com.basketry.catalog.listing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public listing's reads, in plain SQL (the same on H2 and PostgreSQL).
 *
 * <p><b>Visibility</b> (FR-B3, FR-B6; DESIGN.md Section 3, catalog-service):
 * a product is public only if it is active and its merchant is active. A
 * merchant with no {@code merchant_status} row has never changed status and is
 * active ({@code COALESCE(ms.active, TRUE)}). Products with zero stock stay
 * listed; their availability says so.
 */
@Repository
public class ProductQueries {

    private static final String VISIBLE_FROM = """
             FROM product p
             LEFT JOIN merchant_status ms ON ms.merchant_id = p.merchant_id
             LEFT JOIN stock_row_request sr ON sr.product_id = p.id
             WHERE p.active AND COALESCE(ms.active, TRUE)
            """;

    private static final RowMapper<CachedProduct> CACHED_PRODUCT = (rs, row) -> new CachedProduct(
            rs.getLong("id"), rs.getLong("merchant_id"), rs.getString("name"), rs.getString("description"),
            rs.getString("category"), rs.getBigDecimal("price").toPlainString(),
            "CONFLICT".equals(rs.getString("stock_status")));

    private final NamedParameterJdbcTemplate jdbc;

    public ProductQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One page of the public listing, in id order, optionally of one merchant and one category (exact match). */
    @Transactional(readOnly = true)
    public CachedPage publicPage(OptionalLong merchantId, Optional<String> category, int page, int size) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        List<String> filters = new ArrayList<>();
        merchantId.ifPresent(id -> {
            filters.add(" AND p.merchant_id = :merchantId");
            parameters.addValue("merchantId", id);
        });
        category.ifPresent(value -> {
            filters.add(" AND p.category = :category");
            parameters.addValue("category", value);
        });
        String where = VISIBLE_FROM + String.join("", filters);
        List<CachedProduct> content = jdbc.query("SELECT p.id, p.merchant_id, p.name, p.description, p.category,"
                + " p.price, sr.status AS stock_status" + where + " ORDER BY p.id LIMIT :limit OFFSET :offset",
                parameters, CACHED_PRODUCT);
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, parameters, Long.class);
        return new CachedPage(content, total == null ? 0 : total);
    }
}
