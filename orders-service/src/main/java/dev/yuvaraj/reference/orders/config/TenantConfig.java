package dev.yuvaraj.reference.orders.config;

import java.util.Map;

import dev.yuvaraj.reference.security.TenantContext;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Hands the current tenant to Hibernate so it can fill and filter every {@code @TenantId} column.
 *
 * <p>This is what makes tenant isolation a mapping concern instead of a code-review concern.
 * With it in place, {@code SELECT * FROM orders} through JPA is physically incapable of
 * returning another tenant's rows &mdash; there is no code path that omits the predicate,
 * because no code path writes it.
 */
@Configuration
public class TenantConfig {

    @Component
    static class ContextTenantResolver implements CurrentTenantIdentifierResolver<String> {

        @Override
        public String resolveCurrentTenantIdentifier() {
            // Hibernate calls this while opening a session, including for Flyway-adjacent
            // bootstrap work that has no request behind it. Returning the sentinel keeps
            // startup working; it matches no real tenant's rows.
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
