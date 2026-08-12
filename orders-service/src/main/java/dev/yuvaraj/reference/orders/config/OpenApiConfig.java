package dev.yuvaraj.reference.orders.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Swagger UI to Keycloak so the "Authorize" button performs a real authorization-code
 * flow against the running realm. Reviewers can obtain a token and exercise the tenant rules
 * without touching a terminal.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI ordersOpenApi(@Value("${platform.keycloak.auth-url}") String authUrl,
                          @Value("${platform.keycloak.token-url}") String tokenUrl) {
        OAuthFlow authorizationCode = new OAuthFlow()
                .authorizationUrl(authUrl)
                .tokenUrl(tokenUrl)
                .scopes(new Scopes().addString("openid", "OpenID Connect"));

        return new OpenAPI()
                .info(new Info()
                        .title("Orders API")
                        .version("1.0.0")
                        .description("Multi-tenant orders API. Every response is scoped to the "
                                + "tenant_id claim in the presented access token."))
                .components(new Components().addSecuritySchemes("keycloak",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .flows(new OAuthFlows().authorizationCode(authorizationCode))));
    }
}
