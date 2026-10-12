package io.github.savsuth.reservecore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

/** The dev profile seeds two merchants whose documented keys work; their plaintext is never stored. */
@ActiveProfiles("dev")
class DevBootstrapTest extends ApiTestSupport {

    static final String ALICE_KEY = "rcsk_devalice_DEVELOPMENT-ONLY-not-a-secret-alice-0000001";
    static final String BOB_KEY = "rcsk_devbob00_DEVELOPMENT-ONLY-not-a-secret-bob-000000001";

    @Test
    void documentedDevKeysAuthenticateAsSeparateMerchants() throws Exception {
        String sku = "DEV-" + newKey();

        Response aliceCreates = post(ALICE_KEY, "/v1/skus", "{\"sku\": \"" + sku + "\", \"initialQuantity\": 3}");
        Response bobReads = get(BOB_KEY, "/v1/skus/" + sku);

        assertThat(aliceCreates.status()).isEqualTo(201);
        assertThat(get(ALICE_KEY, "/v1/skus/" + sku).status()).isEqualTo(200);
        assertThat(bobReads.status()).isEqualTo(404);
    }

    @Test
    void onlyKeyHashesAreStored() {
        List<String> stored = jdbc.sql("SELECT key_prefix || ' ' || encode(key_hash, 'hex') FROM api_key")
                .query(String.class).list();

        assertThat(stored).contains(
                "devalice 63a23bf0f73332404d89c374461bd8d1bf6b10e18d6395e53400e813134cc4d6",
                "devbob00 a9c516e9be55893ce078a7604f14175f52cc5d9971b31a4539ba31aa09b618ed");
        assertThat(String.join("\n", stored)).doesNotContain("DEVELOPMENT-ONLY");
    }
}
