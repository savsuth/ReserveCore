package io.github.savsuth.reservecore.web;

import io.swagger.v3.oas.annotations.media.Schema;

/** Documentation-only shape of every error body (RFC 9457 problem details plus two members). */
@Schema(name = "Problem", description = "RFC 9457 problem details. `type` is omitted, meaning about:blank.")
public record ProblemResponse(
        @Schema(example = "Conflict") String title,
        @Schema(example = "409") int status,
        @Schema(example = "Requested 3 of SKU HOODIE-M but only 1 available.") String detail,
        @Schema(example = "/v1/reservations") String instance,
        @Schema(description = "Stable machine-readable error code", example = "INSUFFICIENT_STOCK") String code,
        @Schema(description = "Id of the request that produced this body", example = "6f1c2b0e-5c1e-4c55-9d7c-1f8f3b2f8a10")
        String requestId) {

    public static final String MEDIA_TYPE = "application/problem+json";
}
