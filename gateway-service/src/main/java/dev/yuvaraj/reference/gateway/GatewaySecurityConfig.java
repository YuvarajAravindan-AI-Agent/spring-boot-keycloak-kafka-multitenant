package dev.yuvaraj.reference.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Rejects unauthenticated traffic at the edge.
 *
 * <p>The services behind the gateway validate the same token again rather than trusting a
 * header the gateway sets. That is not redundant work: anything on the network that can reach
 * port 8081 directly would otherwise be able to claim any tenant simply by setting a header.
 * The gateway is a convenience and a throttling point, never the only place authorization
 * happens.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(exchange -> exchange
                .pathMatchers("/actuator/health/**").permitAll()
                .anyExchange().authenticated())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> { }));
        return http.build();
    }
}
