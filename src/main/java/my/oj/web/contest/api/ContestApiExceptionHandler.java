package my.oj.web.contest.api;

import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import my.oj.web.auth.UnauthenticatedException;
import my.oj.web.contest.submission.support.ContestSubmissionOverloadedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Errors for the JSON API.
 *
 * <p>Scoped to the API packages by name because {@code AuthenticationExceptionHandler} still
 * answers {@link UnauthenticatedException} with a redirect to the login page, which is right for a
 * rendered page and wrong for a caller expecting JSON. The two cannot both be global. When the
 * page controllers go, that handler goes with them and this one becomes unscoped - until then a
 * new API package has to be added here or its errors fall through to the redirect.
 *
 * <p>The ordering is not decoration. Package scoping decides which controllers an advice may
 * serve, not which advice wins when two both can, and that is resolved by order alone - measured
 * without it, an unauthenticated POST to the submissions endpoint answered 302 to /login instead
 * of 401.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = {
        "my.oj.web.contest.scoreboard.api",
        "my.oj.web.contest.submission.api"
})
public class ContestApiExceptionHandler {

    /**
     * 503 with Retry-After, as the perf endpoint did. This is the admission limiter refusing work
     * it cannot queue, so it is backpressure the caller can act on rather than a failure - a load
     * generator that retries after the stated delay is behaving correctly, and one that treats it
     * as an error reports a fault that did not happen.
     */
    @ExceptionHandler(ContestSubmissionOverloadedException.class)
    public ResponseEntity<Map<String, Object>> handleOverloaded(ContestSubmissionOverloadedException ex,
                                                                HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()))
                .body(body(ex, request));
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthenticated(UnauthenticatedException ex,
                                                                     HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body(ex, request));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(ex, request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception ex, HttpServletRequest request) {
        log.error("API request failed: {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body(ex, request));
    }

    private static Map<String, Object> body(Exception ex, HttpServletRequest request) {
        return Map.of(
                "error", ex.getClass().getSimpleName(),
                "message", ex.getMessage() == null ? "" : ex.getMessage(),
                "path", request.getRequestURI()
        );
    }
}
