package uct8086.ai.core.engine;

/**
 * SSE event pushed to the frontend during streaming agent execution.
 *
 * <p>Event types:
 * <ul>
 *   <li>{@code session} — session created/reused (carries sessionId)</li>
 *   <li>{@code turn} — agent loop turn started (carries turn number)</li>
 *   <li>{@code tool} — a tool was called (carries tool name + result summary)</li>
 *   <li>{@code response} — final assistant response (carries response text)</li>
 *   <li>{@code error} — execution failed (carries error message)</li>
 *   <li>{@code done} — stream complete (carries usage stats)</li>
 * </ul>
 */
public record ChatStreamEvent(String type, String content) {

    public static ChatStreamEvent session(String sessionId) {
        return new ChatStreamEvent("session", sessionId);
    }

    public static ChatStreamEvent turn(int turn) {
        return new ChatStreamEvent("turn", String.valueOf(turn));
    }

    public static ChatStreamEvent tool(String toolName, String resultSummary) {
        return new ChatStreamEvent("tool", toolName + ": " + resultSummary);
    }

    public static ChatStreamEvent response(String text) {
        return new ChatStreamEvent("response", text);
    }

    public static ChatStreamEvent error(String message) {
        return new ChatStreamEvent("error", message);
    }

    public static ChatStreamEvent done(String usage) {
        return new ChatStreamEvent("done", usage);
    }
}
