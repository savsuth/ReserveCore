package io.github.savsuth.reservecore.demo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Minimal ReserveCore HTTP client: one API key, several instances addressed by index (round-robin). */
final class Api {

    /** One HTTP exchange. {@code status} is -1 when the request failed before a response arrived. */
    record Reply(int status, JsonNode body, HttpResponse<String> raw, long nanos, String error) {

        String code() {
            return body == null ? "" : body.path("code").asString("");
        }

        String text(String field) {
            return body == null ? null : body.path(field).asString(null);
        }

        int number(String field) {
            return body.path(field).asInt();
        }

        boolean replayed() {
            return raw != null && raw.headers().firstValue("Idempotent-Replayed").orElse("").equals("true");
        }

        String header(String name) {
            return raw == null ? null : raw.headers().firstValue(name).orElse(null);
        }

        String describe() {
            return status == -1 ? "no response (" + error + ")" : status + " " + code();
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final List<URI> bases;
    private final String apiKey;

    Api(List<URI> bases, String apiKey) {
        this.bases = bases;
        this.apiKey = apiKey;
    }

    Api withKey(String otherKey) {
        return new Api(bases, otherKey);
    }

    int instances() {
        return bases.size();
    }

    URI base(int instance) {
        return bases.get(Math.floorMod(instance, bases.size()));
    }

    Reply get(int instance, String path) {
        return send(request(instance, path).GET());
    }

    Reply post(int instance, String path, String body, String idempotencyKey) {
        HttpRequest.Builder request = request(instance, path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return send(request);
    }

    /** Readiness without an API key; false while the instance is down or not ready. */
    boolean ready(int instance) {
        Reply reply = send(HttpRequest.newBuilder(base(instance).resolve("/actuator/health/readiness"))
                .timeout(Duration.ofSeconds(5)).GET());
        return reply.status() == 200;
    }

    private HttpRequest.Builder request(int instance, String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base(instance).resolve(path)).timeout(Duration.ofSeconds(30));
        if (apiKey != null) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder;
    }

    private Reply send(HttpRequest.Builder request) {
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            long nanos = System.nanoTime() - start;
            JsonNode body = response.body().isBlank() ? null : JSON.readTree(response.body());
            return new Reply(response.statusCode(), body, response, nanos, null);
        } catch (IOException e) {
            return new Reply(-1, null, null, System.nanoTime() - start, e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
