package dev.yuvaraj.reference.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the Keycloak-to-Spring-Security mapping, which is where most "my roles do not work"
 * reports actually live. Runs without Docker or a broker.
 */
class KeycloakJwtAuthenticationConverterTest {

    private final KeycloakJwtAuthenticationConverter converter =
            new KeycloakJwtAuthenticationConverter("orders-api", "tenant_id");

    @Test
    @DisplayName("realm roles become ROLE_ authorities")
    void mapsRealmRoles() {
        Jwt jwt = jwt(Map.of(
                "realm_access", Map.of("roles", List.of("platform-admin"))));

        assertThat(authorities(jwt)).contains("ROLE_platform-admin");
    }

    @Test
    @DisplayName("client roles for this service become ROLE_ authorities")
    void mapsClientRoles() {
        Jwt jwt = jwt(Map.of(
                "resource_access", Map.of(
                        "orders-api", Map.of("roles", List.of("orders:read", "orders:write")))));

        assertThat(authorities(jwt)).contains("ROLE_orders:read", "ROLE_orders:write");
    }

    @Test
    @DisplayName("roles granted on a different client do not leak in")
    void ignoresOtherClientsRoles() {
        // Without the client-id filter, a user who is an admin of some unrelated client would
        // arrive here holding that authority. Keycloak puts every client's roles in the same
        // token, so this is a real boundary and not a hypothetical one.
        Jwt jwt = jwt(Map.of(
                "resource_access", Map.of(
                        "billing-api", Map.of("roles", List.of("billing:admin")))));

        assertThat(authorities(jwt)).doesNotContain("ROLE_billing:admin");
    }

    @Test
    @DisplayName("a token with no roles yields no authorities rather than failing")
    void handlesMissingClaims() {
        assertThat(authorities(jwt(Map.of()))).isEmpty();
    }

    @Test
    @DisplayName("the tenant is read from the configured claim")
    void readsTenantClaim() {
        assertThat(converter.tenantOf(jwt(Map.of("tenant_id", "tenant-a")))).isEqualTo("tenant-a");
        assertThat(converter.tenantOf(jwt(Map.of()))).isNull();
    }

    @Test
    @DisplayName("preferred_username is preferred over the opaque subject")
    void usesPreferredUsernameAsPrincipal() {
        Jwt jwt = jwt(Map.of("preferred_username", "alice"));
        assertThat(converter.convert(jwt).getName()).isEqualTo("alice");

        assertThat(converter.convert(jwt(Map.of())).getName()).isEqualTo("test-subject");
    }

    private List<String> authorities(Jwt jwt) {
        return converter.convert(jwt).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    private Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("test-subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
