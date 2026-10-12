package io.github.savsuth.reservecore.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * An expected API error, rendered as RFC 9457 problem details with a stable {@code code} member.
 * See docs/PLAN.md section 4 for the codes.
 */
public class ApiProblem extends ErrorResponseException {

    public ApiProblem(HttpStatus status, String code, String detail) {
        super(status, problem(status, code, detail), null);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }

    public static ApiProblem invalidQuantity(String field) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY", field + " must be an integer >= 1.");
    }

    public static ApiProblem validation(String detail) {
        return new ApiProblem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
    }

    public static ApiProblem skuNotFound(String sku) {
        return new ApiProblem(HttpStatus.NOT_FOUND, "SKU_NOT_FOUND", "SKU " + sku + " does not exist.");
    }
}
