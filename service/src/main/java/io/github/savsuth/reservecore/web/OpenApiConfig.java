package io.github.savsuth.reservecore.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API-wide OpenAPI metadata. The server is the relative "/" so the generated spec does not depend on the
 * host or port it was generated on (the committed docs/openapi.json is compared byte for byte in a test).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    static final String API_KEY_SCHEME = "apiKey";

    @Bean
    OpenAPI reserveCoreOpenApi() {
        return new OpenAPI()
                .info(new Info().title("ReserveCore API").version("v1").description("""
                        Holds limited stock during checkout: reserve units for a bounded time, then confirm or \
                        cancel; unconfirmed reservations expire. Every /v1 call needs a merchant API key. Create, \
                        confirm and cancel need an Idempotency-Key. Errors are RFC 9457 problem details with a \
                        stable `code` and the `requestId` (also in the X-Request-Id response header)."""))
                .servers(List.of(new Server().url("/").description("The instance serving this document")))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer")
                        .description("Merchant API key: rcsk_<8-char prefix>_<43-char secret>")))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}
