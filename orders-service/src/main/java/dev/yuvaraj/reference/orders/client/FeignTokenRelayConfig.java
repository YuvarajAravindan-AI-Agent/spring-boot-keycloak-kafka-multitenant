package dev.yuvaraj.reference.orders.client;

import feign.RequestInterceptor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Relays the caller's bearer token onto outbound Feign calls.
 *
 * <p>Without this, inventory-service sees an anonymous request and returns 401 — the classic
 * "works in Postman, 401 between services" report. Forwarding the original token keeps the
 * end user's identity and tenant intact across the hop, so authorization is evaluated against
 * the human who made the request rather than against a shared service account with a
 * superset of everyone's permissions.
 */
@Configuration
public class FeignTokenRelayConfig {

    @Bean
    public RequestInterceptor bearerTokenRelay() {
        return template -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken token) {
                template.header("Authorization", "Bearer " + token.getToken().getTokenValue());
            }
        };
    }
}
