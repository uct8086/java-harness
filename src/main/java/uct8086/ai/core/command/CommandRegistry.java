package uct8086.ai.core.command;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Registry for slash commands.
 * Maps to OpenHarness's Command Registry (54 commands).
 */
@Component
public class CommandRegistry {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistry.class);

    private final Map<String, HarnessCommand> commands = new ConcurrentHashMap<>();

    /**
     * Register a command.
     */
    public void register(HarnessCommand command) {
        Objects.requireNonNull(command, "Command cannot be null");
        commands.put(command.getName(), command);
        log.debug("Registered command: /{}", command.getName());
    }

    /**
     * Get a command by name.
     */
    public Optional<HarnessCommand> getCommand(String name) {
        return Optional.ofNullable(commands.get(name));
    }

    /**
     * Execute a command by parsing the input string.
     *
     * @param input   the full input (e.g. "/help arg1 arg2")
     * @param context additional context
     * @return the command output, or null if the input is not a command
     */
    public String execute(String input, Map<String, Object> context) {
        if (input == null || !input.startsWith("/")) {
            return null;
        }

        String[] parts = input.substring(1).split("\\s+", 2);
        String commandName = parts[0];
        List<String> args = parts.length > 1
                ? Arrays.asList(parts[1].split("\\s+"))
                : List.of();

        HarnessCommand command = commands.get(commandName);
        if (command == null) {
            return "Unknown command: /" + commandName + ". Type /help for available commands.";
        }

        try {
            return command.execute(args, context);
        } catch (Exception e) {
            log.error("Command /{} failed", commandName, e);
            return "Command failed: " + e.getMessage();
        }
    }

    /**
     * List all registered commands.
     */
    public List<HarnessCommand> listCommands() {
        return commands.values().stream()
                .sorted(Comparator.comparing(HarnessCommand::getName))
                .toList();
    }

    /**
     * Check if a command exists.
     */
    public boolean hasCommand(String name) {
        return commands.containsKey(name);
    }
}
