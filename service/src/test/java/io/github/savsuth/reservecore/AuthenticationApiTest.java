package io.github.savsuth.reservecore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthenticationApiTest extends ApiTestSupport {

    @Test
    void missingApiKeyIsRejectedWithProblemDetails() throws Exception {
        Response response = get(null, "/v1/skus/ANY");

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.header("Content-Type")).startsWith("application/problem+json");
        assertThat(response.header("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.body().get("code").asString()).isEqualTo("UNAUTHORIZED");
        assertThat(response.body().get("requestId").asString()).isEqualTo(response.header("X-Request-Id"));
    }

    @Test
    void unknownOrMalformedApiKeysAreRejected() throws Exception {
        String unknown = "rcsk_00000000_" + "A".repeat(43);
        Merchant merchant = newMerchant();
        String tampered = merchant.apiKey().substring(0, merchant.apiKey().length() - 1)
                + (merchant.apiKey().endsWith("A") ? "B" : "A");

        for (String key : new String[] {unknown, tampered, "not-a-key", ""}) {
            Response response = get(key, "/v1/skus/ANY");
            assertThat(response.status()).as("key %s", key).isEqualTo(401);
            assertThat(response.body().get("code").asString()).isEqualTo("UNAUTHORIZED");
        }
    }

    @Test
    void revokedApiKeyIsRejected() throws Exception {
        Merchant merchant = newMerchant();
        assertThat(get(merchant.apiKey(), "/v1/skus/ANY").status()).isEqualTo(404);

        jdbc.sql("UPDATE api_key SET revoked_at = now() WHERE merchant_id = :m").param("m", merchant.id()).update();

        Response response = get(merchant.apiKey(), "/v1/skus/ANY");
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.body().get("code").asString()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void devKeysDoNotExistOutsideTheDevProfile() throws Exception {
        for (String key : new String[] {DevBootstrapTest.ALICE_KEY, DevBootstrapTest.BOB_KEY}) {
            assertThat(get(key, "/v1/skus/ANY").status()).isEqualTo(401);
        }
    }

    @Test
    void validApiKeyIsAccepted() throws Exception {
        Merchant merchant = newMerchant();

        Response response = get(merchant.apiKey(), "/v1/skus/ANY");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().get("code").asString()).isEqualTo("SKU_NOT_FOUND");
    }

    @Test
    void clientRequestIdIsEchoedAndInvalidOnesAreReplaced() throws Exception {
        Response echoed = send(request(null, "/v1/skus/ANY").header("X-Request-Id", "client-req.42_A"));
        assertThat(echoed.header("X-Request-Id")).isEqualTo("client-req.42_A");

        Response replaced = send(request(null, "/v1/skus/ANY").header("X-Request-Id", "bad id with spaces"));
        assertThat(replaced.header("X-Request-Id")).matches("[0-9a-f-]{36}");
    }
}
