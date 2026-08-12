package dev.yuvaraj.reference.security;

/**
 * Raised when application code reaches a data access path without a tenant bound.
 *
 * <p>This is deliberately a hard failure rather than a fallback to "no filter". A
 * missing tenant that silently degrades into an unfiltered query is exactly the
 * cross-tenant read this reference implementation exists to demonstrate.
 */
public class TenantMissingException extends IllegalStateException {

    public TenantMissingException(String message) {
        super(message);
    }
}
