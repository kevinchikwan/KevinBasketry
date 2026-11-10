package com.basketry.catalog.product;

import com.basketry.catalog.stockrow.StockRowStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reads of the management and internal endpoints, in plain SQL. Unlike
 * the public listing they show inactive products and products of inactive
 * merchants, and they are never cached.
 */
@Repository
public class ProductReads {

    private static final String MANAGED_SELECT = "SELECT p.id, p.merchant_id, p.name, p.description, p.category,"
            + " p.price, p.active, p.created_at, p.updated_at, sr.status AS stock_status"
            + " FROM product p LEFT JOIN stock_row_request sr ON sr.product_id = p.id";

    private static final RowMapper<ManagedProduct> MANAGED = (rs, row) -> new ManagedProduct(
            rs.getLong("id"), rs.getLong("merchant_id"), rs.getString("name"), rs.getString("description"),
            rs.getString("category"), rs.getBigDecimal("price"), rs.getBoolean("active"),
            stockRow(rs.getString("stock_status")),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<InternalProduct> INTERNAL = (rs, row) -> new InternalProduct(
            rs.getLong("id"), rs.getLong("merchant_id"), rs.getString("name"), rs.getBigDecimal("price"),
            rs.getBoolean("active"), rs.getBoolean("merchant_active"), stockRow(rs.getString("stock_status")));

    private final NamedParameterJdbcTemplate jdbc;

    public ProductReads(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Optional<ManagedProduct> managed(long id) {
        return jdbc.query(MANAGED_SELECT + " WHERE p.id = :id", new MapSqlParameterSource("id", id), MANAGED)
                .stream().findFirst();
    }

    /** One page in id order: every product, or one merchant's. Inactive products included. */
    @Transactional(readOnly = true)
    public ManagedPage managedPage(OptionalLong merchantId, int page, int size) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        String where = "";
        if (merchantId.isPresent()) {
            where = " WHERE p.merchant_id = :merchantId";
            parameters.addValue("merchantId", merchantId.getAsLong());
        }
        List<ManagedProduct> content = jdbc.query(MANAGED_SELECT + where + " ORDER BY p.id LIMIT :limit OFFSET :offset",
                parameters, MANAGED);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM product p" + where, parameters, Long.class);
        return new ManagedPage(content, total == null ? 0 : total);
    }

    /**
     * The given products that exist, in id order, each once. A merchant with no
     * merchant_status row is active (version 0).
     */
    @Transactional(readOnly = true)
    public List<InternalProduct> internal(Collection<Long> ids) {
        return jdbc.query("SELECT p.id, p.merchant_id, p.name, p.price, p.active,"
                        + " COALESCE(ms.active, TRUE) AS merchant_active, sr.status AS stock_status"
                        + " FROM product p"
                        + " LEFT JOIN merchant_status ms ON ms.merchant_id = p.merchant_id"
                        + " LEFT JOIN stock_row_request sr ON sr.product_id = p.id"
                        + " WHERE p.id IN (:ids) ORDER BY p.id",
                new MapSqlParameterSource("ids", ids), INTERNAL);
    }

    /** Every product has its request from its creation (same transaction), so null never occurs in practice. */
    private static StockRowStatus stockRow(String status) {
        return status == null ? null : StockRowStatus.valueOf(status);
    }

    public record ManagedPage(List<ManagedProduct> content, long totalElements) {
    }
}
