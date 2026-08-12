package dev.yuvaraj.reference.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the authenticated token's tenant to {@link TenantContext} for the duration of a request.
 *
 * <p>Ordered after Spring Security's filter chain so that the {@link Jwt} has already been
 * validated &mdash; the tenant is taken from a signature-checked claim, never from a request
 * header a caller could set. The {@code finally} block is not optional: servlet containers
 * reuse threads, so a tenant left behind here is a tenant the next request inherits.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    private final KeycloakJwtAuthenticationConverter converter;

    public TenantContextFilter(KeycloakJwtAuthenticationConverter converter) {
        this.converter = converter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean bound = false;
        if (authentication instanceof JwtAuthenticationToken token) {
            String tenant = converter.tenantOf(token.getToken());
            if (tenant != null && !tenant.isBlank()) {
                TenantContext.set(tenant);
                bound = true;
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (bound) {
                TenantContext.clear();
            }
        }
    }
}
