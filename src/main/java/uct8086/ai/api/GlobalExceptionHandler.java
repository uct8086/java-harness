package uct8086.ai.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uct8086.ai.common.exception.CostLimitExceededException;
import uct8086.ai.common.exception.Uct8086Exception;

import java.util.Map;

/**
 * Global exception handler that maps domain exceptions to clear HTTP responses.
 *
 * <p>Without this, an exception thrown in a controller (or during async dispatch)
 * falls through to Spring Security and surfaces as a generic 403 "Access Denied",
 * hiding the real cause from the frontend.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Cost quota exceeded → 429 with a human-readable message so the frontend can
     * surface the circuit-breaker notice to the user.
     */
    @ExceptionHandler(CostLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleCostLimitExceeded(CostLimitExceededException e) {
        log.warn("Cost limit exceeded: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of(
                        "error", "cost_limit_exceeded",
                        "message", e.getMessage()
                ));
    }

    /**
     * Other domain exceptions → 400 with the message.
     */
    @ExceptionHandler(Uct8086Exception.class)
    public ResponseEntity<Map<String, Object>> handleUct8086Exception(Uct8086Exception e) {
        log.warn("Domain exception: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "error", "bad_request",
                        "message", e.getMessage()
                ));
    }
}
