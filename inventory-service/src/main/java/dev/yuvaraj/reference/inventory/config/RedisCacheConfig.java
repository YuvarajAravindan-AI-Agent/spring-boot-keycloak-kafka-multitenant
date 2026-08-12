package dev.yuvaraj.reference.inventory.config;

import java.time.Duration;

import dev.yuvaraj.reference.inventory.api.dto.StockLevel;
import dev.yuvaraj.reference.security.TenantContext;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis cache setup for tenant-scoped reads.
 *
 * <p>The security-relevant part is the key. A cache on a multi-tenant read is a second copy of
 * the data that does <em>not</em> go through Hibernate, so the {@code @TenantId} predicate that
 * protects the database protects nothing here. Cache under {@code stock::SKU-DOCK} and tenant B
 * is served tenant A's stock level from Redis on a hit &mdash; a cross-tenant leak that no
 * amount of reviewing the repository layer would catch, and that passes every test written
 * against a single tenant. Putting the tenant in the key is the entire fix, and it makes
 * eviction naturally per-tenant as well.
 */
@Configuration
public class RedisCacheConfig {

    public static final String STOCK_CACHE = "stock";

    /**
     * Default key generator for any cached method that does not name its own key. Hot paths
     * spell the key out instead (see {@code InventoryService.TENANT_SKU_KEY}) so that a read
     * and its matching eviction cannot drift apart when one of them gains a parameter.
     */
    @Bean
    public KeyGenerator tenantAwareKeyGenerator() {
        return (target, method, params) -> {
            StringBuilder key = new StringBuilder(TenantContext.requireTenant());
            for (Object param : params) {
                key.append(':').append(param);
            }
            return key.toString();
        };
    }

    @Bean
    public RedisCacheConfiguration cacheConfiguration(ObjectMapper objectMapper) {
        // A serializer bound to the concrete type, rather than GenericJackson2JsonRedisSerializer
        // with default typing. Two reasons:
        //
        //  1. StockLevel is a record, and records are final. Jackson's NON_FINAL default typing
        //     writes no @class property for final types, then demands one on the way back --
        //     the cache writes successfully and every read fails with "missing type id
        //     property '@class'". Only ever on a cache hit, so it survives local development.
        //  2. Default typing deserialises whatever class name the payload names. Anyone who can
        //     write to Redis can then choose which class this service instantiates, which is a
        //     deserialisation gadget waiting to happen. A fixed target type removes the choice.
        //
        // It also keeps the stored JSON clean enough to read with redis-cli, which matters when
        // you are trying to prove what the cache actually holds.
        var valueSerializer = new Jackson2JsonRedisSerializer<>(objectMapper, StockLevel.class);

        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                .computePrefixWith(cacheName -> cacheName + "::")
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(valueSerializer));
    }
}
