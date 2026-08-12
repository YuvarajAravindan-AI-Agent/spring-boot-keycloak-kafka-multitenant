package dev.yuvaraj.reference.orders.api;

import java.net.URI;

import dev.yuvaraj.reference.orders.service.OrderCommandService.InsufficientStockException;
import dev.yuvaraj.reference.security.TenantMissingException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain failures onto RFC 7807 problem responses.
 *
 * <p>Messages stay free of tenant identifiers and row counts: an error body is the easiest
 * place to leak the existence of another tenant's data.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InsufficientStockException.class)
    ProblemDetail onInsufficientStock(InsufficientStockException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Insufficient stock");
        problem.setType(URI.create("https://errors.example.com/orders/insufficient-stock"));
        return problem;
    }

    @ExceptionHandler(TenantMissingException.class)
    ProblemDetail onTenantMissing(TenantMissingException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "The presented token carries no tenant claim.");
        problem.setTitle("Tenant not resolved");
        problem.setType(URI.create("https://errors.example.com/auth/tenant-missing"));
        return problem;
    }
}
