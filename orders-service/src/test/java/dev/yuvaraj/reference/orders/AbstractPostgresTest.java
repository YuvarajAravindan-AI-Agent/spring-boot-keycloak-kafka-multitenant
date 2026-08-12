package dev.yuvaraj.reference.orders;

import dev.yuvaraj.reference.orders.client.InventoryClient;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for tests that need a real Postgres.
 *
 * <p>The container is a static singleton started once per JVM rather than a JUnit
 * {@code @Container} per class: Flyway migration plus schema validation costs a few seconds,
 * and paying it per test class is what turns an integration suite into one nobody runs.
 *
 * <p>Real Postgres rather than H2 on purpose. The behaviour under test is Hibernate's
 * generated SQL — {@code @TenantId} predicates, {@code LIMIT}/{@code OFFSET} placement, how a
 * {@code join fetch} interacts with pagination. An in-memory database with a compatibility
 * mode is a different SQL dialect wearing a costume, and it hides exactly these differences.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("docker")
public abstract class AbstractPostgresTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("orders")
                    .withUsername("orders")
                    .withPassword("orders");

    static {
        POSTGRES.start();
    }

    /** Kafka is not the subject of these tests; the producer is stubbed out. */
    @MockitoBean
    @SuppressWarnings("rawtypes")
    protected KafkaTemplate kafkaTemplate;

    /** Feign would otherwise need inventory-service running. */
    @MockitoBean
    protected InventoryClient inventoryClient;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
