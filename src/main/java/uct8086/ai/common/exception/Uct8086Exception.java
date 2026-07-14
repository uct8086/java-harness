package uct8086.ai.common.exception;

/**
 * Base exception for all UCT8086-AI errors.
 */
public class Uct8086Exception extends RuntimeException {

    public Uct8086Exception(String message) {
        super(message);
    }

    public Uct8086Exception(String message, Throwable cause) {
        super(message, cause);
    }
}
