package dev.yuvaraj.reference.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Wires the Keycloak token converter and tenant propagation into any service on the classpath.
 *
 * <p>Registered through {@code AutoConfiguration.imports} rather than component scanning,
 * because this package sits outside each service's own base package. Every bean is
 * {@code @ConditionalOnMissingBean} so a service can override one without forking the module.
 */
@AutoConfiguration
@EnableConfigurationProperties(TenantSecurityProperties.class)
public class CommonSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public KeycloakJwtAuthenticationConverter keycloakJwtAuthenticationConverter(
            TenantSecurityProperties properties) {
        return new KeycloakJwtAuthenticationConverter(
                properties.getClientId(), properties.getTenantClaim());
    }

    @Bean
    @ConditionalOnMissingBean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<TenantContextFilter>
            tenantContextFilter(KeycloakJwtAuthenticationConverter converter) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                new TenantContextFilter(converter));
        // After Spring Security (LOWEST_PRECEDENCE - 100 by default) so the JWT is validated first.
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 50);
        return registration;
    }
}
