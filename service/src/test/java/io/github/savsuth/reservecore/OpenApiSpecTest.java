package io.github.savsuth.reservecore;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The committed docs/openapi.json must equal what the running application serves. After an intended API
 * change, regenerate it with {@code ./mvnw verify -Dopenapi.update=true} and commit the result.
 */
class OpenApiSpecTest extends ApiTestSupport {

    static final Path SPEC = Path.of("..", "docs", "openapi.json");

    @Test
    void committedSpecMatchesTheRunningApplication() throws Exception {
        Response served = get(null, "/v3/api-docs");
        assertThat(served.status()).isEqualTo(200);
        String live = json.writerWithDefaultPrettyPrinter().writeValueAsString(served.body()) + "\n";

        if (Boolean.getBoolean("openapi.update")) {
            Files.writeString(SPEC, live);
        }

        assertThat(SPEC).as("docs/openapi.json is missing; run ./mvnw verify -Dopenapi.update=true").exists();
        assertThat(live).as("docs/openapi.json is out of date; run ./mvnw verify -Dopenapi.update=true and commit it")
                .isEqualTo(Files.readString(SPEC));
    }

    @Test
    void specDocumentsEveryOperationAuthAndIdempotencyKeys() throws Exception {
        JsonNode spec = get(null, "/v3/api-docs").body();
        JsonNode paths = spec.get("paths");

        assertThat(paths.get("/v1/skus").has("post")).isTrue();
        assertThat(paths.get("/v1/skus/{sku}").has("get")).isTrue();
        assertThat(paths.get("/v1/skus/{sku}/movements").has("get")).isTrue();
        assertThat(paths.get("/v1/reservations").has("post")).isTrue();
        assertThat(paths.get("/v1/reservations/{id}").has("get")).isTrue();
        assertThat(spec.get("components").get("securitySchemes").get("apiKey").get("scheme").asString())
                .isEqualTo("bearer");
        for (JsonNode operation : new JsonNode[] {paths.get("/v1/reservations").get("post"),
                paths.get("/v1/reservations/{id}/confirm").get("post"),
                paths.get("/v1/reservations/{id}/cancel").get("post")}) {
            JsonNode key = null;
            for (JsonNode parameter : operation.get("parameters")) {
                if (parameter.get("name").asString().equals("Idempotency-Key")) {
                    key = parameter;
                }
            }
            assertThat(key).as(operation.get("operationId").asString()).isNotNull();
            assertThat(key.get("in").asString()).isEqualTo("header");
            assertThat(key.get("required").asBoolean()).isTrue();
            assertThat(operation.get("responses").get("409").get("content").get("application/problem+json")
                    .get("schema").get("$ref").asString()).isEqualTo("#/components/schemas/Problem");
        }
        assertThat(paths.get("/v1/reservations").get("post").get("responses").get("201").get("content")
                .get("application/json").get("schema").get("$ref").asString())
                .isEqualTo("#/components/schemas/ReservationView");
    }
}
