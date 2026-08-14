package uct8086.ai.common.exception;

/**
 * Thrown when a user's cost quota (hard limit) has been exceeded and the request
 * is rejected (circuit breaker tripped).
 */
public class CostLimitExceededException extends Uct8086Exception {

    public CostLimitExceededException(String message) {
        super(message);
    }
}
