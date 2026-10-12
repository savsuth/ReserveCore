package io.github.savsuth.reservecore.idempotency;

import io.github.savsuth.reservecore.web.ApiProblem;
import io.github.savsuth.reservecore.web.RequestIdFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a write operation at most once per (merchant, operation, Idempotency-Key), in the same transaction
 * as the record of its response. Rules: docs/PLAN.md section 7.
 *
 * <p>The record is inserted first. A concurrent request with the same key blocks on the primary key until
 * the first commits (then it replays or reports a conflict) or rolls back (then it executes itself).
 * Successes and business rejections ({@link ApiProblem} thrown by the operation) are recorded and
 * committed; any other exception rolls everything back, including the record, so a retry executes afresh.
 * Operations must therefore make their rejection decisions before writing anything, unless the write is meant
 * to be committed with the rejection (expire on touch).
 *
 * <p>Every API transaction starts with {@code SET LOCAL lock_timeout}, so a request waits at most
 * {@code reservecore.lock-timeout} for a row lock; the resulting SQLSTATE 55P03 is answered with 503 SERVICE_BUSY.
 */
@Component
public class Idempotency {

    public enum Operation { CREATE_RESERVATION, CONFIRM_RESERVATION, CANCEL_RESERVATION }

    /** What an operation returns on success. */
    public record Outcome(HttpStatus status, Object body) {}

    public static final String KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Pattern VALID_KEY = Pattern.compile("[\\x20-\\x7E]{1,255}");

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final JsonMapper json;
    private final String setLockTimeout;
    private final MeterRegistry meters;

    public Idempotency(JdbcClient jdbc, PlatformTransactionManager transactionManager, JsonMapper json,
            @Value("${reservecore.lock-timeout}") Duration lockTimeout, MeterRegistry meters) {
        if (lockTimeout.isNegative() || lockTimeout.isZero()) {
            throw new IllegalArgumentException("reservecore.lock-timeout must be > 0");
        }
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.json = json;
        this.meters = meters;
        // SET does not take bind parameters; the value is a number we formatted ourselves.
        this.setLockTimeout = "SET LOCAL lock_timeout = '" + lockTimeout.toMillis() + "ms'";
    }

    /** Checks the header value before any work is done; nothing is recorded for a missing or invalid key. */
    public static String requireKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISSING",
                    KEY_HEADER + " header is required for this operation.");
        }
        if (!VALID_KEY.matcher(key).matches()) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
                    KEY_HEADER + " must be 1 to 255 printable ASCII characters.");
        }
        return key;
    }

    /**
     * @param request canonical form of the operation's inputs; same key with a different value is a conflict
     * @param instance request path, used as the problem {@code instance}
     * @param location builds the Location header of a 201 from its JSON body, or null for no header
     */
    public ResponseEntity<String> execute(UUID merchantId, Operation operation, String key, String request,
            String instance, Supplier<Outcome> work, Function<String, URI> location) {
        byte[] requestHash = sha256(request);
        Stored stored;
        try {
            stored = runOnce(merchantId, operation, key, requestHash, instance, work);
        } catch (ApiProblem rejection) {
            count(operation, String.valueOf(rejection.getBody().getProperties().get("code")), false);
            throw rejection;
        } catch (RuntimeException e) {
            count(operation, isLockTimeout(e) ? "SERVICE_BUSY" : "ERROR", false);
            throw e;
        }
        count(operation, stored.status() >= 400 ? json.readTree(stored.body()).path("code").asString("ERROR") : "SUCCESS",
                stored.replayed());
        return stored.toResponse(location);
    }

    private Stored runOnce(UUID merchantId, Operation operation, String key, byte[] requestHash, String instance,
            Supplier<Outcome> work) {
        return transaction.execute(status -> {
            jdbc.sql(setLockTimeout).update();
            int inserted = jdbc.sql("""
                    INSERT INTO idempotency_record (merchant_id, operation, idempotency_key, request_hash)
                    VALUES (:m, :op, :key, :hash)
                    ON CONFLICT DO NOTHING
                    """).param("m", merchantId).param("op", operation.name()).param("key", key)
                    .param("hash", requestHash).update();
            if (inserted == 0) {
                return replay(merchantId, operation, key, requestHash);
            }
            int responseStatus;
            Object responseBody;
            try {
                Outcome outcome = work.get();
                responseStatus = outcome.status().value();
                responseBody = outcome.body();
            } catch (ApiProblem rejection) {
                responseStatus = rejection.getStatusCode().value();
                responseBody = problemBody(rejection.getBody(), instance);
            }
            String body = jdbc.sql("""
                    UPDATE idempotency_record SET response_status = :status, response_body = CAST(:body AS jsonb)
                    WHERE merchant_id = :m AND operation = :op AND idempotency_key = :key
                    RETURNING response_body::text
                    """).param("status", responseStatus).param("body", json.writeValueAsString(responseBody))
                    .param("m", merchantId).param("op", operation.name()).param("key", key)
                    .query(String.class).single();
            return new Stored(responseStatus, body, false);
        });
    }

    /** {@code reservecore.operations}: one count per write request, by operation, outcome code and replay. */
    private void count(Operation operation, String outcome, boolean replayed) {
        Counter.builder("reservecore.operations")
                .description("Reservation writes by operation, outcome (SUCCESS or the error code) and replay")
                .tag("operation", operation.name()).tag("outcome", outcome).tag("replayed", String.valueOf(replayed))
                .register(meters).increment();
    }

    private static boolean isLockTimeout(RuntimeException e) {
        return e instanceof DataAccessException dae && dae.getMostSpecificCause() instanceof SQLException sql
                && "55P03".equals(sql.getSQLState());
    }

    private Stored replay(UUID merchantId, Operation operation, String key, byte[] requestHash) {
        record Existing(byte[] requestHash, int responseStatus, String responseBody) {}
        Existing existing = jdbc.sql("""
                SELECT request_hash, response_status, response_body::text AS response_body
                FROM idempotency_record WHERE merchant_id = :m AND operation = :op AND idempotency_key = :key
                """).param("m", merchantId).param("op", operation.name()).param("key", key)
                .query(Existing.class).single();
        if (!MessageDigest.isEqual(existing.requestHash(), requestHash)) {
            throw new ApiProblem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "This " + KEY_HEADER + " was already used with a different request.");
        }
        return new Stored(existing.responseStatus(), existing.responseBody(), true);
    }

    /** The same problem-details members the HTTP layer renders, plus the original request id. */
    private static Map<String, Object> problemBody(ProblemDetail problem, String instance) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        body.put("detail", problem.getDetail());
        body.put("instance", instance);
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        body.put("requestId", MDC.get(RequestIdFilter.MDC_KEY));
        return body;
    }

    private record Stored(int status, String body, boolean replayed) {

        ResponseEntity<String> toResponse(Function<String, URI> location) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(status >= 400 ? MediaType.APPLICATION_PROBLEM_JSON : MediaType.APPLICATION_JSON);
            if (replayed) {
                headers.set(REPLAYED_HEADER, "true");
            }
            if (status == HttpStatus.CREATED.value() && location != null) {
                headers.setLocation(location.apply(body));
            }
            return new ResponseEntity<>(body, headers, status);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
