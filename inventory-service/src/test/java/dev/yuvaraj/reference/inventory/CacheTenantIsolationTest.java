package dev.yuvaraj.reference.inventory;

import dev.yuvaraj.reference.inventory.api.dto.StockLevel;
import dev.yuvaraj.reference.inventory.config.RedisCacheConfig;
import dev.yuvaraj.reference.inventory.service.InventoryService;
import dev.yuvaraj.reference.security.TenantContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Redis layer respects tenant boundaries and that a write invalidates the read.
 *
 * <p>These two properties are usually assumed rather than tested, and both fail silently:
 * a cache key without the tenant serves the wrong customer's numbers only on a hit, and a
 * missing eviction only shows up between a write and the TTL. Neither reproduces on a
 * developer's empty cache.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
class CacheTenantIsolationTest {

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String SKU = "SKU-DOCK";

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("inventory")
                    .withUsername("inventory")
                    .withPassword("inventory");

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @MockitoBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private CacheManager cacheManager;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Test
    @DisplayName("two tenants reading the same SKU occupy different cache keys")
    void cacheKeysAreTenantScoped() throws Exception {
        TenantContext.callWith(TENANT_A, () -> inventory.getStock(SKU));
        TenantContext.callWith(TENANT_B, () -> inventory.getStock(SKU));

        var cache = cacheManager.getCache(RedisCacheConfig.STOCK_CACHE);
        assertThat(cache).isNotNull();
        assertThat(cache.get(TENANT_A + ":" + SKU)).isNotNull();
        assertThat(cache.get(TENANT_B + ":" + SKU)).isNotNull();
    }

    @Test
    @DisplayName("a reservation evicts that tenant's entry and leaves the other tenant's alone")
    void reservationEvictsOnlyTheWritingTenant() throws Exception {
        int before = TenantContext.callWith(TENANT_A, () -> inventory.getStock(SKU)).available();
        int otherBefore = TenantContext.callWith(TENANT_B, () -> inventory.getStock(SKU)).available();

        boolean taken = TenantContext.callWith(TENANT_A, () -> inventory.reserve(SKU, 5));
        assertThat(taken).isTrue();

        StockLevel afterA = TenantContext.callWith(TENANT_A, () -> inventory.getStock(SKU));
        StockLevel afterB = TenantContext.callWith(TENANT_B, () -> inventory.getStock(SKU));

        assertThat(afterA.available())
                .as("the read must reflect the write, not the pre-write cache entry")
                .isEqualTo(before - 5);
        assertThat(afterB.available())
                .as("tenant B's stock and cache entry are untouched")
                .isEqualTo(otherBefore);
    }

    @Test
    @DisplayName("a refused reservation does not change the stock level")
    void refusedReservationLeavesStockAlone() throws Exception {
        int before = TenantContext.callWith(TENANT_B, () -> inventory.getStock(SKU)).available();

        boolean taken = TenantContext.callWith(TENANT_B, () -> inventory.reserve(SKU, before + 1_000));

        assertThat(taken).isFalse();
        assertThat(TenantContext.callWith(TENANT_B, () -> inventory.getStock(SKU)).available())
                .isEqualTo(before);
    }
}
