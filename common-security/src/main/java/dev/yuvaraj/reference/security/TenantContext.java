package dev.yuvaraj.reference.security;

/**
 * Holds the tenant for the current thread.
 *
 * <p>Populated from the {@code tenant_id} JWT claim on inbound HTTP requests
 * ({@link TenantContextFilter}) and from the {@code X-Tenant-Id} record header on
 * inbound Kafka records, so that the same tenant travels with a unit of work whether
 * it arrived over HTTP or over a topic.
 *
 * <p>Hibernate reads this through a {@code CurrentTenantIdentifierResolver} to fill the
 * {@code @TenantId} column on every read and write. Nothing else in the application is
 * allowed to pass a tenant id explicitly &mdash; that is the whole point. A query that
 * takes the tenant as a parameter is a query someone can call with the wrong one.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** @return the tenant bound to this thread, never {@code null} */
    public static String requireTenant() {
        String tenant = CURRENT.get();
        if (tenant == null || tenant.isBlank()) {
            throw new TenantMissingException(
                    "No tenant bound to the current thread. Every data access path must run "
                            + "inside a request or Kafka listener that established one.");
        }
        return tenant;
    }

    /** @return the tenant bound to this thread, or {@code null} if there is none */
    public static String currentTenantOrNull() {
        return CURRENT.get();
    }

    public static void set(String tenantId) {
        CURRENT.set(tenantId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs {@code body} with {@code tenantId} bound, restoring the previous value afterwards. */
    public static <T> T callWith(String tenantId, java.util.concurrent.Callable<T> body) throws Exception {
        String previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return body.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
