package uct8086.ai.common.model;

/**
 * A path-level permission rule.
 * Used to allow or deny operations on specific paths.
 */
public record PathRule(
        String pattern,
        boolean allow
) {
    public static PathRule allow(String pattern) {
        return new PathRule(pattern, true);
    }

    public static PathRule deny(String pattern) {
        return new PathRule(pattern, false);
    }
}
