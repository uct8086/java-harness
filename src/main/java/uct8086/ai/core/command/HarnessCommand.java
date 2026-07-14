package uct8086.ai.core.command;

import java.util.List;
import java.util.Map;

/**
 * Interface for slash commands in the harness.
 * Maps to OpenHarness's Command system (/help, /commit, /plan, etc.)
 *
 * <p>Commands are invoked by the user with a leading slash, e.g.:
 * <pre>/commit -m "fix bug"</pre>
 */
public interface HarnessCommand {

    /**
     * The command name (without the leading slash).
     * e.g. "help", "commit", "plan"
     */
    String getName();

    /**
     * Short description shown in command listing.
     */
    String getDescription();

    /**
     * Whether this command modifies state (write) or is read-only.
     */
    default boolean isReadOnly() {
        return true;
    }

    /**
     * Execute the command.
     *
     * @param args    the command arguments (parsed from the input)
     * @param context additional context (session, config, etc.)
     * @return the command output
     */
    String execute(List<String> args, Map<String, Object> context);
}
