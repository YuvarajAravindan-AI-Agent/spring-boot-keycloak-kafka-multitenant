package dev.yuvaraj.reference.gateway;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Keeps the public demo standing up.
 *
 * <p>Two of the endpoints here are <em>deliberately</em> slow &mdash; that is the entire point of
 * the N+1 comparison &mdash; and the demo credentials are published in the README. An unguarded
 * public deployment is therefore trivially wedged by a handful of concurrent visitors, or filled
 * up by one person in a loop. Two independent guards:
 *
 * <ul>
 *   <li><b>A per-IP token bucket.</b> Not a fixed cooldown: the demo script fires several
 *       requests back to back, and a flat cooldown would 429 the second one while a burst of
 *       tokens lets a legitimate walkthrough through and still throttles a loop.</li>
 *   <li><b>A global in-flight cap on the expensive paths.</b> Rejected immediately rather than
 *       queued &mdash; a visitor waiting behind a queue concludes the demo is broken, which is
 *       worse than an honest 429.</li>
 * </ul>
 *
 * <p>Runs before Spring Security (order &minus;200 against the security chain's &minus;100) so
 * that a flood is rejected before it costs a signature verification.
 *
 * <p>In-memory on purpose: this is one instance behind one reverse proxy. The moment there are
 * two, this needs to move to the Redis rate limiter, and the bucket map needs eviction &mdash;
 * as written it grows with distinct client IPs, which is bounded enough for a demo and would be
 * a slow leak in production.
 */
@Component
@ConfigurationProperties(prefix = "platform.demo.guard")
public class DemoGuardFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DemoGuardFilter.class);

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicInteger inFlightHeavy = new AtomicInteger();

    /** Off unless explicitly enabled, so local development is never throttled. */
    private boolean enabled = false;
    /** Tokens a single client may spend in a burst. */
    private int burst = 20;
    /** One token is returned to each bucket every this many milliseconds. */
    private long refillMillis = 1_500;
    /** Concurrent requests allowed across all clients on the expensive paths. */
    private int maxConcurrentHeavy = 4;

    @Override
    public int getOrder() {
        return -200;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        if (path.startsWith("/actuator/health")) {
            return chain.filter(exchange);
        }

        String client = clientIp(request);
        if (!buckets.computeIfAbsent(client, k -> new Bucket(burst, refillMillis)).tryConsume()) {
            log.debug("rate limited client={} path={}", client, path);
            return reject(exchange, "Rate limit exceeded. This is a shared public demo — "
                    + "please wait a moment. Run it locally with `make up` for no limits.");
        }

        if (!isHeavy(path, request)) {
            return chain.filter(exchange);
        }

        if (inFlightHeavy.incrementAndGet() > maxConcurrentHeavy) {
            inFlightHeavy.decrementAndGet();
            return reject(exchange, "The demo is busy running another visitor's query. "
                    + "The NAIVE and JOIN_FETCH strategies are slow by design — that is the point "
                    + "of the comparison — so concurrency here is capped.");
        }

        return chain.filter(exchange)
                .doFinally(signal -> inFlightHeavy.decrementAndGet());
    }

    /**
     * The paths that cost real database work: the seed writer, and any listing that asked for a
     * strategy other than the fixed one.
     */
    private boolean isHeavy(String path, ServerHttpRequest request) {
        if (path.startsWith("/api/admin/")) {
            return true;
        }
        if (!path.startsWith("/api/orders")) {
            return false;
        }
        String strategy = request.getQueryParams().getFirst("strategy");
        return strategy != null && !"TWO_QUERY".equalsIgnoreCase(strategy);
    }

    /**
     * Trusts {@code X-Forwarded-For} only because a reverse proxy is known to sit in front and
     * overwrite it. Exposed directly, this header is caller-controlled and every attacker gets
     * their own bucket.
     */
    private String clientIp(ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddress() == null
                ? "unknown"
                : request.getRemoteAddress().getAddress().getHostAddress();
    }

    private Mono<Void> reject(ServerWebExchange exchange, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, "5");

        String body = """
                {"type":"https://errors.example.com/demo/rate-limited",\
                "title":"Too many requests","status":429,"detail":"%s"}"""
                .formatted(detail.replace("\"", "'"));
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /** Token bucket. Synchronised rather than lock-free: contention here is trivially low. */
    private static final class Bucket {
        private final int capacity;
        private final long refillMillis;
        private double tokens;
        private long lastRefill;

        Bucket(int capacity, long refillMillis) {
            this.capacity = capacity;
            this.refillMillis = refillMillis;
            this.tokens = capacity;
            this.lastRefill = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            tokens = Math.min(capacity, tokens + (double) (now - lastRefill) / refillMillis);
            lastRefill = now;
            if (tokens < 1) {
                return false;
            }
            tokens -= 1;
            return true;
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setBurst(int burst) {
        this.burst = burst;
    }

    public void setRefillMillis(long refillMillis) {
        this.refillMillis = refillMillis;
    }

    public void setMaxConcurrentHeavy(int maxConcurrentHeavy) {
        this.maxConcurrentHeavy = maxConcurrentHeavy;
    }
}
