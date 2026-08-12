package dev.yuvaraj.reference.inventory.config;

import java.util.Map;

import dev.yuvaraj.reference.security.TenantContext;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/** @see dev.yuvaraj.reference.inventory.config.RedisCacheConfig for why the cache needs the same treatment. */
@Configuration
public class TenantConfig {

    @Component
    static class ContextTenantResolver implements CurrentTenantIdentifierResolver<String> {

        @Override
        public String resolveCurrentTenantIdentifier() {
            String tenant = TenantContext.currentTenantOrNull();
            return tenant != null ? tenant : "__unbound__";
        }

        @Override
        public boolean validateExistingCurrentSessions() {
            return false;
        }
    }

    @Bean
    HibernatePropertiesCustomizer tenantIdentifierResolverCustomizer(ContextTenantResolver resolver) {
        return (Map<String, Object> properties) ->
                properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }
}
