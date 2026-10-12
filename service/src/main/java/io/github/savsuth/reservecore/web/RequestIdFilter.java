package io.github.savsuth.reservecore.web;

import io.github.savsuth.reservecore.auth.ApiKeyAuthInterceptor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id: the client's {@code X-Request-Id} when it is safe to echo, otherwise a
 * new UUID. The id is returned on every response, put in the logging MDC, and stored on the request.
 * When the request ends, one access log line records method, path, status, duration, request id and
 * (once authenticated) merchant id. Headers are never logged, so API keys stay out of the logs. Actuator requests
 * (probes and metric scrapes, several per second across a fleet) are not access-logged; they are measured by
 * {@code http_server_requests} instead.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    public static final String ATTRIBUTE = "reservecore.requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        String requestId = supplied != null && SAFE.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            if (!request.getRequestURI().startsWith("/actuator/")) {
                log.atInfo().setMessage("request completed")
                        .addKeyValue("method", request.getMethod())
                        .addKeyValue("path", request.getRequestURI())
                        .addKeyValue("status", response.getStatus())
                        .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
                        .log();
            }
            MDC.remove(ApiKeyAuthInterceptor.MDC_KEY);
            MDC.remove(MDC_KEY);
        }
    }
}
