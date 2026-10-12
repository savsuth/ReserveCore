package io.github.savsuth.reservecore;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

@ExtendWith(OutputCaptureExtension.class)
class ObservabilityTest extends ApiTestSupport {

    @Test
    void everyRequestLogsOneJsonAccessLineWithoutTheApiKey(CapturedOutput output) throws Exception {
        Merchant merchant = newMerchant();
        String authenticatedId = "obs-" + UUID.randomUUID();
        String anonymousId = "obs-" + UUID.randomUUID();

        Response created = send(request(merchant.apiKey(), "/v1/skus")
                .header("X-Request-Id", authenticatedId).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"sku\": \"LOGGED\", \"initialQuantity\": 2}")));
        Response rejected = send(request(null, "/v1/skus/LOGGED").header("X-Request-Id", anonymousId));

        assertThat(created.status()).isEqualTo(201);
        assertThat(rejected.status()).isEqualTo(401);
        JsonNode access = awaitAccessLine(output, authenticatedId);
        assertThat(access.get("log").get("level").asString()).isEqualTo("INFO");
        assertThat(access.get("service").get("name").asString()).isEqualTo("reservecore");
        assertThat(access.get("method").asString()).isEqualTo("POST");
        assertThat(access.get("path").asString()).isEqualTo("/v1/skus");
        assertThat(access.get("status").asInt()).isEqualTo(201);
        assertThat(access.get("merchantId").asString()).isEqualTo(merchant.id().toString());
        assertThat(access.get("durationMs").asLong()).isNotNegative();

        JsonNode anonymous = awaitAccessLine(output, anonymousId);
        assertThat(anonymous.get("status").asInt()).isEqualTo(401);
        assertThat(anonymous.has("merchantId")).isFalse();

        String secret = merchant.apiKey().substring(merchant.apiKey().lastIndexOf('_') + 1);
        assertThat(output.getAll()).doesNotContain(merchant.apiKey()).doesNotContain(secret);
        assertThat(accessLines(output, authenticatedId)).isEqualTo(1);
    }

    @org.springframework.beans.factory.annotation.Autowired
    io.micrometer.core.instrument.MeterRegistry meters;

    @Test
    void businessOutcomesAreCountedByOperationCodeAndReplay() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"COUNTED\", \"initialQuantity\": 1}");
        String body = "{\"sku\": \"COUNTED\", \"quantity\": 1}";
        String key = newKey();
        double success = outcome("SUCCESS", false), insufficient = outcome("INSUFFICIENT_STOCK", false),
                replayed = outcome("SUCCESS", true);

        assertThat(post(merchant.apiKey(), "/v1/reservations", body, key).status()).isEqualTo(201);
        assertThat(post(merchant.apiKey(), "/v1/reservations", body, newKey()).status()).isEqualTo(409);
        assertThat(post(merchant.apiKey(), "/v1/reservations", body, key).header("Idempotent-Replayed")).isEqualTo("true");

        assertThat(outcome("SUCCESS", false)).isEqualTo(success + 1);
        assertThat(outcome("INSUFFICIENT_STOCK", false)).isEqualTo(insufficient + 1);
        assertThat(outcome("SUCCESS", true)).isEqualTo(replayed + 1);
        String scrape = http.send(request(null, "/actuator/prometheus").GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        assertThat(scrape).contains("reservecore_operations_total{").contains("outcome=\"INSUFFICIENT_STOCK\"");
    }

    @Test
    void actuatorProbesAreNotAccessLogged(CapturedOutput output) throws Exception {
        Merchant merchant = newMerchant();
        String probeId = "obs-probe-" + UUID.randomUUID();
        String apiId = "obs-api-" + UUID.randomUUID();

        send(request(null, "/actuator/health/liveness").header("X-Request-Id", probeId));
        send(request(merchant.apiKey(), "/v1/skus/NONE").header("X-Request-Id", apiId));

        awaitAccessLine(output, apiId);
        assertThat(accessLines(output, probeId)).isZero();
    }

    private double outcome(String outcome, boolean replayed) {
        var counter = meters.find("reservecore.operations").tag("operation", "CREATE_RESERVATION")
                .tag("outcome", outcome).tag("replayed", String.valueOf(replayed)).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void prometheusEndpointExposesHttpWorkerAndPoolMetricsWithoutAnApiKey() throws Exception {
        Merchant merchant = newMerchant();
        post(merchant.apiKey(), "/v1/skus", "{\"sku\": \"METERED\", \"initialQuantity\": 1}");

        java.net.http.HttpResponse<String> scrape = http.send(request(null, "/actuator/prometheus").GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body())
                .contains("http_server_requests_seconds_count{")
                .contains("uri=\"/v1/skus\"")
                .contains("reservecore_expiry_expired_total")
                .contains("reservecore_expiry_batch_failures_total")
                .contains("reservecore_expiry_batch_duration_seconds")
                .contains("reservecore_expiry_lag_seconds")
                .contains("reservecore_expiry_last_success_age_seconds")
                .contains("hikaricp_connections_active");
    }

    @Test
    void livenessAndReadinessProbesReportUpAndReadinessIncludesTheDatabase() throws Exception {
        Response liveness = get(null, "/actuator/health/liveness");
        Response readiness = get(null, "/actuator/health/readiness");

        assertThat(liveness.status()).isEqualTo(200);
        assertThat(liveness.body().get("status").asString()).isEqualTo("UP");
        assertThat(readiness.status()).isEqualTo(200);
        assertThat(readiness.body().get("status").asString()).isEqualTo("UP");
        assertThat(readiness.body().get("components").get("db").get("status").asString()).isEqualTo("UP");
    }

    /** The response can reach the client before the filter logs; wait (bounded) for the line to appear. */
    private JsonNode awaitAccessLine(CapturedOutput output, String requestId) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            Optional<JsonNode> line = jsonLines(output).filter(accessLineFor(requestId)).findFirst();
            if (line.isPresent()) {
                return line.get();
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("no access log line for request " + requestId);
    }

    private long accessLines(CapturedOutput output, String requestId) {
        return jsonLines(output).filter(accessLineFor(requestId)).count();
    }

    private static Predicate<JsonNode> accessLineFor(String requestId) {
        return node -> requestId.equals(node.path("requestId").asString(null))
                && "request completed".equals(node.path("message").asString(null));
    }

    private java.util.stream.Stream<JsonNode> jsonLines(CapturedOutput output) {
        return output.getOut().lines().filter(line -> line.startsWith("{")).map(json::readTree);
    }
}
