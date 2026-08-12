package dev.yuvaraj.reference.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for how a Keycloak token maps onto this platform's authorities and tenant.
 */
@ConfigurationProperties(prefix = "platform.security")
public class TenantSecurityProperties {

    /**
     * Keycloak client id this service authenticates as. Client roles are read from
     * {@code resource_access.<clientId>.roles}; roles granted on other clients are ignored.
     */
    private String clientId = "orders-api";

    /**
     * Token claim carrying the tenant. Populated in Keycloak by a user-attribute protocol
     * mapper, so it is signed as part of the token rather than supplied by the caller.
     */
    private String tenantClaim = "tenant_id";

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getTenantClaim() {
        return tenantClaim;
    }

    public void setTenantClaim(String tenantClaim) {
        this.tenantClaim = tenantClaim;
    }
}
