package io.github.savsuth.reservecore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the running application over HTTP on a random port, against Testcontainers PostgreSQL.
 * Each test creates its own merchant, so tests sharing the cached context and database do not interfere.
 * Scheduled workers are off; worker tests call the pass methods directly. The small batch size makes
 * a worker pass span several batches.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"reservecore.workers.enabled=false", "reservecore.expiry.batch-size=10"})
@Import(TestcontainersConfiguration.class)
public abstract class ApiTestSupport {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${local.server.port}")
    int port;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected JsonMapper json;

    protected final HttpClient http = HttpClient.newHttpClient();

    protected record Merchant(UUID id, String apiKey) {}

    protected record Response(int status, HttpHeaders headers, JsonNode body) {

        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    /** Inserts a merchant and an API key the way the dev bootstrap will: prefix plus SHA-256 of the full key. */
    protected Merchant newMerchant() throws Exception {
        UUID merchantId = jdbc.sql("INSERT INTO merchant (name) VALUES ('test') RETURNING id")
                .query(UUID.class).single();
        byte[] prefixBytes = new byte[4];
        byte[] secret = new byte[32];
        RANDOM.nextBytes(prefixBytes);
        RANDOM.nextBytes(secret);
        String prefix = HexFormat.of().formatHex(prefixBytes);
        String apiKey = "rcsk_" + prefix + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
        jdbc.sql("INSERT INTO api_key (merchant_id, key_prefix, key_hash) VALUES (:m, :p, :h)")
                .param("m", merchantId).param("p", prefix).param("h", hash).update();
        return new Merchant(merchantId, apiKey);
    }

    protected Response get(String apiKey, String path) throws Exception {
        return send(request(apiKey, path).GET());
    }

    protected Response post(String apiKey, String path, String body) throws Exception {
        return send(request(apiKey, path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    /** POST with an {@code Idempotency-Key} header (omitted when null). */
    protected Response post(String apiKey, String path, String body, String idempotencyKey) throws Exception {
        HttpRequest.Builder builder = request(apiKey, path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return send(builder);
    }

    /** Moves expires_at of every ACTIVE reservation of the merchant into the past (database time, no sleep). */
    protected void expireAllActive(Merchant merchant) {
        jdbc.sql("""
                UPDATE reservation SET expires_at = clock_timestamp() - interval '1 second'
                WHERE merchant_id = :m AND status = 'ACTIVE'
                """).param("m", merchant.id()).update();
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    protected HttpRequest.Builder request(String apiKey, String path) {
        // A bounded wait, so a request stuck on a lock fails the test instead of hanging the build.
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(java.time.Duration.ofSeconds(30));
        if (apiKey != null) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder;
    }

    protected Response send(HttpRequest.Builder request) throws Exception {
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode body = response.body().isEmpty() ? null : json.readTree(response.body());
        return new Response(response.statusCode(), response.headers(), body);
    }
}
