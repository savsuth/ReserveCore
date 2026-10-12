package io.github.savsuth.reservecore.auth;

import io.github.savsuth.reservecore.web.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Authenticates {@code Authorization: Bearer rcsk_<prefix>_<secret>} against {@code api_key}: lookup by
 * prefix, constant-time comparison of the SHA-256 of the full key. On success the merchant id is stored
 * as a request attribute. The key itself is never logged or stored.
 */
@Component
public class ApiKeyAuthInterceptor implements HandlerInterceptor {

    public static final String MERCHANT_ID = "reservecore.merchantId";
    /** Logging MDC key; removed by {@code RequestIdFilter} when the request ends. */
    public static final String MDC_KEY = "merchantId";

    private static final Pattern KEY = Pattern.compile("rcsk_([a-z0-9]{8})_[A-Za-z0-9_-]{43}");

    private final JdbcClient jdbc;

    public ApiKeyAuthInterceptor(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader("Authorization");
        String key = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
        Matcher matcher = KEY.matcher(key);
        if (!matcher.matches()) {
            throw unauthorized(response);
        }
        record StoredKey(UUID merchantId, byte[] keyHash) {}
        StoredKey stored = jdbc.sql("""
                SELECT merchant_id, key_hash FROM api_key WHERE key_prefix = :prefix AND revoked_at IS NULL
                """).param("prefix", matcher.group(1)).query(StoredKey.class).optional().orElse(null);
        if (stored == null || !MessageDigest.isEqual(stored.keyHash(), sha256(key))) {
            throw unauthorized(response);
        }
        request.setAttribute(MERCHANT_ID, stored.merchantId());
        MDC.put(MDC_KEY, stored.merchantId().toString());
        return true;
    }

    private static ApiProblem unauthorized(HttpServletResponse response) {
        response.setHeader("WWW-Authenticate", "Bearer");
        return new ApiProblem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "A valid API key is required.");
    }

    private static byte[] sha256(String key) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
