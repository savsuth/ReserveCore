package io.github.savsuth.reservecore.web;

import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as problem details carrying {@code code} and {@code requestId}: our own
 * {@link ApiProblem}s, Spring MVC's built-in errors (unreadable body, unknown path, wrong method), and
 * unexpected exceptions, which become a 500 without internal details.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    /**
     * A row lock not obtained within the transaction's lock_timeout (SQLSTATE 55P03): the transaction rolled back,
     * nothing was recorded for idempotency, so the client can retry with the same key.
     */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Object> handleDataAccess(DataAccessException ex, WebRequest request) {
        if (ex.getMostSpecificCause() instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "The item is busy. Retry the same request, with the same Idempotency-Key, shortly.");
            problem.setProperty("code", "SERVICE_BUSY");
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, "1");
            log.warn("Lock wait timed out; answered 503 SERVICE_BUSY");
            return handleExceptionInternal(ex, problem, headers, HttpStatus.SERVICE_UNAVAILABLE, request);
        }
        return handleUnexpected(ex, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal error. Quote the requestId when reporting it.");
        problem.setProperty("code", "INTERNAL_ERROR");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
                problem.setProperty("code", defaultCode(statusCode));
            }
            problem.setProperty("requestId",
                    request.getAttribute(RequestIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));
        }
        return response;
    }

    private static String defaultCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "VALIDATION_FAILED";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_REJECTED";
        };
    }
}
