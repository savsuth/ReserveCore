package io.github.savsuth.reservecore.reservation;

import io.github.savsuth.reservecore.auth.ApiKeyAuthInterceptor;
import io.github.savsuth.reservecore.idempotency.Idempotency;
import io.github.savsuth.reservecore.idempotency.Idempotency.Outcome;
import io.github.savsuth.reservecore.reservation.ReservationService.Transition;
import io.github.savsuth.reservecore.web.ApiProblem;
import io.github.savsuth.reservecore.web.ProblemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/v1/reservations")
@Tag(name = "Reservations", description = "Hold stock during checkout, then confirm or cancel")
class ReservationController {

    record CreateReservationRequest(String sku, Integer quantity, Integer holdSeconds) {}

    private final ReservationService reservations;
    private final HoldProperties hold;
    private final Idempotency idempotency;
    private final JsonMapper json;

    ReservationController(ReservationService reservations, HoldProperties hold, Idempotency idempotency,
            JsonMapper json) {
        this.reservations = reservations;
        this.hold = hold;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Operation(summary = "Reserve units of a SKU", description = "Holds quantity units for holdSeconds (default 600, 5 to 3600). Idempotent per Idempotency-Key: a retry replays the stored response.")
    @ApiResponse(responseCode = "201", description = "Reserved; Location is /v1/reservations/{id}", headers = @Header(name = "Idempotent-Replayed", description = "true when this is the stored response of an earlier request with the same Idempotency-Key", schema = @Schema(type = "string")), content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReservationView.class)))
    @ApiResponse(responseCode = "400", description = "INVALID_QUANTITY, VALIDATION_FAILED (sku missing, holdSeconds out of range, malformed JSON), IDEMPOTENCY_KEY_MISSING or IDEMPOTENCY_KEY_INVALID", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHORIZED: missing, malformed, unknown or revoked API key", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "404", description = "SKU_NOT_FOUND", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "409", description = "INSUFFICIENT_STOCK or IDEMPOTENCY_KEY_REUSED", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "503", description = "SERVICE_BUSY: a row lock was not obtained within reservecore.lock-timeout (2 s); retry with the same Idempotency-Key after Retry-After", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping
    ResponseEntity<String> create(@RequestAttribute(ApiKeyAuthInterceptor.MERCHANT_ID) UUID merchantId,
            @RequestHeader(name = Idempotency.KEY_HEADER, required = false) @Parameter(in = ParameterIn.HEADER, name = Idempotency.KEY_HEADER, required = true, description = "1 to 255 printable ASCII characters, scoped to merchant and operation") String key,
            @RequestBody CreateReservationRequest request, HttpServletRequest http) {
        String idempotencyKey = Idempotency.requireKey(key);
        if (request.sku() == null) {
            throw ApiProblem.validation("sku is required.");
        }
        if (request.quantity() == null || request.quantity() < 1) {
            throw ApiProblem.invalidQuantity("quantity");
        }
        int holdSeconds = request.holdSeconds() == null ? hold.defaultSeconds() : request.holdSeconds();
        if (holdSeconds < hold.minSeconds() || holdSeconds > hold.maxSeconds()) {
            throw ApiProblem.validation(
                    "holdSeconds must be between " + hold.minSeconds() + " and " + hold.maxSeconds() + ".");
        }
        // The request as sent is the idempotency fingerprint, so an omitted holdSeconds stays omitted.
        return idempotency.execute(merchantId, Idempotency.Operation.CREATE_RESERVATION, idempotencyKey,
                json.writeValueAsString(request), http.getRequestURI(),
                () -> new Outcome(HttpStatus.CREATED,
                        reservations.create(merchantId, request.sku(), request.quantity(), holdSeconds)),
                body -> URI.create("/v1/reservations/" + json.readTree(body).get("id").asString()));
    }

    @Operation(summary = "Confirm a reservation", description = "ACTIVE to CONFIRMED: held units become sold. Rejected at or after expiresAt (database time).")
    @ApiResponse(responseCode = "200", description = "Confirmed", headers = @Header(name = "Idempotent-Replayed", description = "true when this is the stored response of an earlier request with the same Idempotency-Key", schema = @Schema(type = "string")), content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReservationView.class)))
    @ApiResponse(responseCode = "400", description = "IDEMPOTENCY_KEY_MISSING or IDEMPOTENCY_KEY_INVALID", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHORIZED: missing, malformed, unknown or revoked API key", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "404", description = "RESERVATION_NOT_FOUND", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "409", description = "RESERVATION_EXPIRED (an overdue hold, expired by this request), INVALID_STATE_TRANSITION (body has currentStatus) or IDEMPOTENCY_KEY_REUSED", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "503", description = "SERVICE_BUSY: a row lock was not obtained within reservecore.lock-timeout (2 s); retry with the same Idempotency-Key after Retry-After", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping("/{id}/confirm")
    ResponseEntity<String> confirm(@RequestAttribute(ApiKeyAuthInterceptor.MERCHANT_ID) UUID merchantId,
            @RequestHeader(name = Idempotency.KEY_HEADER, required = false) @Parameter(in = ParameterIn.HEADER, name = Idempotency.KEY_HEADER, required = true, description = "1 to 255 printable ASCII characters, scoped to merchant and operation")
            String key,
            @PathVariable String id, HttpServletRequest http) {
        return transition(merchantId, key, id, http, Idempotency.Operation.CONFIRM_RESERVATION, Transition.CONFIRM);
    }

    @Operation(summary = "Cancel a reservation", description = "ACTIVE to CANCELLED: held units become available again. Rejected at or after expiresAt; the expiration worker releases those units.")
    @ApiResponse(responseCode = "200", description = "Cancelled", headers = @Header(name = "Idempotent-Replayed", description = "true when this is the stored response of an earlier request with the same Idempotency-Key", schema = @Schema(type = "string")), content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReservationView.class)))
    @ApiResponse(responseCode = "400", description = "IDEMPOTENCY_KEY_MISSING or IDEMPOTENCY_KEY_INVALID", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHORIZED: missing, malformed, unknown or revoked API key", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "404", description = "RESERVATION_NOT_FOUND", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "409", description = "RESERVATION_EXPIRED (an overdue hold, expired by this request), INVALID_STATE_TRANSITION (body has currentStatus) or IDEMPOTENCY_KEY_REUSED", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "503", description = "SERVICE_BUSY: a row lock was not obtained within reservecore.lock-timeout (2 s); retry with the same Idempotency-Key after Retry-After", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping("/{id}/cancel")
    ResponseEntity<String> cancel(@RequestAttribute(ApiKeyAuthInterceptor.MERCHANT_ID) UUID merchantId,
            @RequestHeader(name = Idempotency.KEY_HEADER, required = false) @Parameter(in = ParameterIn.HEADER, name = Idempotency.KEY_HEADER, required = true, description = "1 to 255 printable ASCII characters, scoped to merchant and operation")
            String key,
            @PathVariable String id, HttpServletRequest http) {
        return transition(merchantId, key, id, http, Idempotency.Operation.CANCEL_RESERVATION, Transition.CANCEL);
    }

    private ResponseEntity<String> transition(UUID merchantId, String key, String id, HttpServletRequest http,
            Idempotency.Operation operation, Transition transition) {
        String idempotencyKey = Idempotency.requireKey(key);
        UUID reservationId = parseId(id);
        return idempotency.execute(merchantId, operation, idempotencyKey, "reservation=" + reservationId,
                http.getRequestURI(),
                () -> new Outcome(HttpStatus.OK, reservations.transition(merchantId, reservationId, transition)),
                null);
    }

    @Operation(summary = "Read a reservation", description = "Shows the effective status: a hold at or past expiresAt reads as EXPIRED, with reclaimPending true until its units are released.")
    @ApiResponse(responseCode = "200", description = "The reservation", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReservationView.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHORIZED: missing, malformed, unknown or revoked API key", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "404", description = "RESERVATION_NOT_FOUND", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @GetMapping("/{id}")
    ReservationView get(@RequestAttribute(ApiKeyAuthInterceptor.MERCHANT_ID) UUID merchantId,
            @PathVariable String id) {
        return reservations.get(merchantId, parseId(id));
    }

    private static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ReservationService.reservationNotFound();
        }
    }
}
