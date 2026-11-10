package com.basketry.catalog.inventory;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code basketry.inventory} in application.yml: inventory-service's
 * /internal/stock (DESIGN.md Section 3).
 *
 * @param baseUrl          from {@code BASKETRY_INVENTORY_URL}
 * @param connectTimeout   for every call
 * @param readTimeout      for every call; a listing waits for the stock read
 * @param skipAfterFailure after a failed stock read, listings skip inventory-service for this long
 */
@ConfigurationProperties("basketry.inventory")
public record InventoryProperties(String baseUrl, Duration connectTimeout, Duration readTimeout,
                                  Duration skipAfterFailure) {
}
