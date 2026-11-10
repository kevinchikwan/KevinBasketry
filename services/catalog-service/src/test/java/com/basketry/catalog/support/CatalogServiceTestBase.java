package com.basketry.catalog.support;

import com.basketry.catalog.cache.CacheProperties;
import com.basketry.catalog.cache.ListingInvalidator;
import com.basketry.catalog.cache.ProductListCache;
import com.basketry.catalog.cache.ProductListCacheGuard;
import com.basketry.catalog.inventory.InventoryClient;
import com.basketry.catalog.inventory.InventoryProperties;
import com.basketry.catalog.listing.ListedProduct;
import com.basketry.catalog.listing.ProductListPage;
import com.basketry.catalog.listing.ProductListing;
import com.basketry.catalog.listing.ProductQueries;
import com.basketry.catalog.listing.StockAvailability;
import com.basketry.catalog.merchantstatus.MerchantStatusRepository;
import com.basketry.catalog.merchantstatus.MerchantStatusService;
import com.basketry.catalog.product.CreateProductCommand;
import com.basketry.catalog.product.ProductRepository;
import com.basketry.catalog.product.ProductService;
import com.basketry.catalog.product.ProductView;
import com.basketry.catalog.stockrow.StockRowRequest;
import com.basketry.catalog.stockrow.StockRowRequestRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every Spring test of catalog-service: the real application on the test
 * database (H2, or PostgreSQL in postgresTest), the test cache (the in-memory
 * fake, or a real Redis in postgresTest) and a fake inventory-service over real
 * HTTP, all emptied before each test.
 *
 * <p>The application's guards remember failures (they skip Redis or
 * inventory-service for a while), so a test that makes Redis fail builds its
 * own stack on its own fake ({@link #onOwnCache}), and a test that makes
 * inventory-service fail builds its own {@link StockAvailability}. The shared
 * beans never see a failure.
 */
@SpringBootTest
@Import(TestCacheConfig.class)
public abstract class CatalogServiceTestBase {

    protected static final long MERCHANT = 3;
    protected static final long OTHER_MERCHANT = 4;

    /** The value of basketry.internal.api-key in the test application.properties. */
    public static final String API_KEY = "test-only-internal-key-not-a-secret-000";

    @DynamicPropertySource
    static void inventoryServer(DynamicPropertyRegistry registry) {
        registry.add("basketry.inventory.base-url", () -> FakeInventoryServer.shared().baseUrl());
    }

    protected final FakeInventoryServer inventory = FakeInventoryServer.shared();

    @Autowired
    protected ProductService productService;

    @Autowired
    protected ProductRepository products;

    @Autowired
    protected StockRowRequestRepository stockRowRequests;

    @Autowired
    protected ProductListing listing;

    @Autowired
    protected ProductQueries queries;

    @Autowired
    protected StockAvailability stockAvailability;

    @Autowired
    protected MerchantStatusService merchantStatuses;

    @Autowired
    protected MerchantStatusRepository merchantStatusRepository;

    /** The fake in the default run, Redis in postgresTest. */
    @Autowired
    protected ProductListCache cache;

    @Autowired
    protected CacheProperties cacheProperties;

    @Autowired
    protected InventoryClient inventoryClient;

    @Autowired
    protected InventoryProperties inventoryProperties;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected Clock clock;

    @BeforeEach
    void emptyTheDatabaseTheCacheAndInventory() {
        jdbc.update("DELETE FROM stock_row_request");
        jdbc.update("DELETE FROM product");
        jdbc.update("DELETE FROM merchant_status");
        if (cache instanceof InMemoryProductListCache fake) {
            fake.clear();
        } else {
            redis.execute((RedisCallback<Object>) connection -> {
                connection.serverCommands().flushDb();
                return null;
            });
        }
        inventory.reset();
    }

    protected static CreateProductCommand command(long merchantId, String name, String price) {
        return new CreateProductCommand(merchantId, name, "A description", "Groceries", new BigDecimal(price));
    }

    protected ProductView givenProduct(long merchantId, String name, String price) {
        return productService.create(command(merchantId, name, price));
    }

    protected ProductView givenProduct(long merchantId, String name) {
        return givenProduct(merchantId, name, "2.50");
    }

    /** The first page of the public listing, 20 a page. */
    protected ProductListPage publicListing() {
        return listing.page(OptionalLong.empty(), Optional.empty(), 0, 20);
    }

    protected ProductListPage publicListingOf(long merchantId) {
        return listing.page(OptionalLong.of(merchantId), Optional.empty(), 0, 20);
    }

    protected static List<String> names(ProductListPage page) {
        return page.content().stream().map(ListedProduct::name).toList();
    }

    protected StockRowRequest stockRowRequestOf(long productId) {
        return stockRowRequests.findById(productId).orElseThrow();
    }

    /** A product row changed behind the service's back: no invalidation, as if by hand. */
    protected void changePriceInTheDatabaseOnly(long productId, String price) {
        jdbc.update("UPDATE product SET price = ? WHERE id = ?", new BigDecimal(price), productId);
    }

    /**
     * The application's services rebuilt on their own fake cache, with the real
     * database and inventory-service. For tests that make the cache fail or
     * place a race inside it, in the default run and in postgresTest alike.
     */
    protected OwnCache onOwnCache(Clock guardClock) {
        InMemoryProductListCache fake = new InMemoryProductListCache();
        ProductListCacheGuard guard = new ProductListCacheGuard(fake, cacheProperties, guardClock);
        ListingInvalidator invalidator = new ListingInvalidator(guard);
        return new OwnCache(fake, guard,
                new ProductListing(queries, guard, stockAvailability, json),
                new ProductService(products, stockRowRequests, invalidator, transactionManager, clock),
                new MerchantStatusService(merchantStatusRepository, invalidator, clock));
    }

    protected OwnCache onOwnCache() {
        return onOwnCache(Clock.systemUTC());
    }

    /** A listing with its own availability (and so its own memory of failures), on the shared cache. */
    protected ProductListing listingWith(StockAvailability availability) {
        return new ProductListing(queries, new ProductListCacheGuard(cache, cacheProperties), availability, json);
    }

    public record OwnCache(InMemoryProductListCache fake, ProductListCacheGuard guard, ProductListing listing,
                           ProductService products, MerchantStatusService merchantStatuses) {

        public ProductListPage publicListing() {
            return listing.page(OptionalLong.empty(), Optional.empty(), 0, 20);
        }
    }
}
