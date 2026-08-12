package dev.yuvaraj.reference.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Edge for the platform.
 *
 * <p>Reactive (WebFlux), unlike the two servlet services behind it — which is why this module
 * deliberately does <em>not</em> depend on {@code common-security}. That module registers a
 * servlet {@code Filter} and a {@code ThreadLocal} tenant context, neither of which is correct
 * on a Netty event loop where one thread serves many requests. Sharing the jar would compile
 * and then leak tenants under concurrency.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
