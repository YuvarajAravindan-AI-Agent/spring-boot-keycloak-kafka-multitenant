package dev.yuvaraj.reference.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Turns a Keycloak access token into a Spring Security authentication.
 *
 * <p>Keycloak does not put roles in the {@code scope} claim that Spring's default
 * converter reads, so out of the box every authenticated user arrives with the single
 * authority {@code SCOPE_profile} and every {@code hasRole(...)} check fails. Roles live in
 * two other places instead:
 *
 * <pre>
 *   "realm_access":    { "roles": ["platform-admin"] }              &lt;- realm roles
 *   "resource_access": { "orders-api": { "roles": ["orders:write"] } } &lt;- client roles
 * </pre>
 *
 * <p>Both are read here and prefixed with {@code ROLE_}. Client roles are scoped to the
 * client id this service authenticates as, so a role granted on a different client does
 * not leak in.
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String REALM_ACCESS = "realm_access";
    private static final String RESOURCE_ACCESS = "resource_access";
    private static final String ROLES = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    private final String clientId;
    private final String tenantClaim;

    public KeycloakJwtAuthenticationConverter(String clientId, String tenantClaim) {
        this.clientId = clientId;
        this.tenantClaim = tenantClaim;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.addAll(realmRoles(jwt));
        authorities.addAll(clientRoles(jwt));
        return new JwtAuthenticationToken(jwt, authorities, principalName(jwt));
    }

    /** The tenant this token is scoped to, or {@code null} when the claim is absent. */
    public String tenantOf(Jwt jwt) {
        Object value = jwt.getClaim(tenantClaim);
        return value == null ? null : value.toString();
    }

    private Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim(REALM_ACCESS);
        return toAuthorities(realmAccess);
    }

    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> clientRoles(Jwt jwt) {
        Map<String, Object> resourceAccess = jwt.getClaim(RESOURCE_ACCESS);
        if (resourceAccess == null) {
            return List.of();
        }
        Object forThisClient = resourceAccess.get(clientId);
        if (!(forThisClient instanceof Map)) {
            return List.of();
        }
        return toAuthorities((Map<String, Object>) forThisClient);
    }

    @SuppressWarnings("unchecked")
    private Collection<GrantedAuthority> toAuthorities(Map<String, Object> claim) {
        if (claim == null) {
            return List.of();
        }
        Object roles = claim.get(ROLES);
        if (!(roles instanceof Collection)) {
            return List.of();
        }
        return ((Collection<Object>) roles).stream()
                .map(String::valueOf)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .toList();
    }

    private String principalName(Jwt jwt) {
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        return preferredUsername != null ? preferredUsername : jwt.getSubject();
    }
}
