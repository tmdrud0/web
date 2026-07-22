package my.oj.web.perf;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Slf4j
@RestControllerAdvice
@Profile("perf")
public class PerfExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception ex, HttpServletRequest request) {
        log.error("Perf request failed: {}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "error", ex.getClass().getSimpleName(),
                        "message", ex.getMessage() == null ? "" : ex.getMessage(),
                        "path", request.getRequestURI()
                ));
    }
}
