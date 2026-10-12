package io.github.savsuth.reservecore.inventory;

import io.github.savsuth.reservecore.auth.ApiKeyAuthInterceptor;
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
import java.net.URI;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/skus")
@Tag(name = "SKUs", description = "Merchant inventory and its movement history")
class InventoryController {

    /** Same rule as the CHECK constraint on inventory.sku. */
    static final Pattern SKU = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    record CreateSkuRequest(String sku, Integer initialQuantity) {}

    private final InventoryService inventory;

    InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @Operation(summary = "Create a SKU with its initial stock", description = "All initial stock starts available. Not idempotent: retrying returns 409 SKU_ALREADY_EXISTS.")
    @ApiResponse(responseCode = "201", description = "Created; Location is /v1/skus/{sku}", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryView.class)))
    @ApiResponse(responseCode = "400", description = "INVALID_QUANTITY (initialQuantity missing or < 1) or VALIDATION_FAILED (sku format, malformed JSON)", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHORIZED: missing, malformed, unknown or revoked API key", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "409", description = "SKU_ALREADY_EXISTS", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @PostMapping
    ResponseEntity<InventoryView> create(@RequestAttribute(ApiKeyAuthInterceptor.MERCHANT_ID) UUID merchantId,
            @RequestBody CreateSkuRequest request) {
        if (request.sku() == null || !SKU.matcher(request.sku()).matches()) {
            throw ApiProblem.validation("sku must be 1 to 64 characters from A-Z a-z 0-9 . _ -");
        }
        if (request.initialQuantity() == null || request.initialQuantity() < 1) {
            throw ApiProblem.invalidQuantity("initialQuantity");
        }
        InventoryView created = inventory.create(merchantId, request.sku(), request.initialQuantity());
        return ResponseEntity.created(URI.create("/v1/skus/" + created.sku())).body(created);
    }

    @Operation(summary = "Movement history of a SKU", description = "Every stock change, newest first. Pass nextBefore from a page as before to get the next, older page.")
    @ApiResponse(responseCode = "200", description = "One page of movements", content = @Content(mediaType = "application/json", schema = @Schema(implementation = MovementPage.class)))
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (limit outside 1..200, before < 1)", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHORIZED: missing, malformed, unknown or revoked API key", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "404", description = "SKU_NOT_FOUND", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @GetMapping("/{sku}/movements")
    MovementPage movements(@RequestAttribute(ApiKeyAuthInterceptor.MERCHANT_ID) UUID merchantId,
            @PathVariable String sku,
            @Parameter(description = "Page size, 1 to 200") @RequestParam(defaultValue = "50") int limit,
            @Parameter(description = "Return movements with an id below this cursor (nextBefore of the previous page)")
            @RequestParam(required = false) Long before) {
        if (limit < 1 || limit > 200) {
            throw ApiProblem.validation("limit must be between 1 and 200.");
        }
        if (before != null && before < 1) {
            throw ApiProblem.validation("before must be a movement id from a previous page.");
        }
        return inventory.movements(merchantId, sku, limit, before == null ? Long.MAX_VALUE : before);
    }

    @Operation(summary = "Availability of a SKU")
    @ApiResponse(responseCode = "200", description = "Current quantities", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryView.class)))
    @ApiResponse(responseCode = "401", description = "UNAUTHORIZED: missing, malformed, unknown or revoked API key", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @ApiResponse(responseCode = "404", description = "SKU_NOT_FOUND", content = @Content(mediaType = ProblemResponse.MEDIA_TYPE, schema = @Schema(implementation = ProblemResponse.class)))
    @GetMapping("/{sku}")
    InventoryView get(@RequestAttribute(ApiKeyAuthInterceptor.MERCHANT_ID) UUID merchantId, @PathVariable String sku) {
        return inventory.get(merchantId, sku);
    }
}
