package uct8086.ai.core.config;

import lombok.Getter;
import lombok.Setter;
import uct8086.ai.common.enums.PermissionMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the harness.
 * Maps to OpenHarness's multi-layer config system.
 */
@Setter
@ConfigurationProperties(prefix = "uct8086.ai")
public class HarnessProperties {

    /** Permission mode for the agent */
    @Getter
    private PermissionMode permissionMode = PermissionMode.DEFAULT;

    /** Maximum number of agent loop iterations */
    @Getter
    private int maxTurns = 50;

    /** Maximum number of past messages to inject into the prompt as conversation history */
    @Getter
    private int maxHistoryMessages = 10;

    /** Whether to enable API retry with exponential backoff */
    private boolean retryEnabled = true;

    /** Maximum API retry attempts */
    private int maxRetries = 3;

    /** Initial retry delay in milliseconds */
    private long retryDelayMs = 1000;

    /** Whether to enable parallel tool execution */
    private boolean parallelToolExecution = true;

    /** Whether to enable context compression (auto-compact) */
    private boolean contextCompression = true;

    /** Token threshold for triggering context compression */
    private int compressionThreshold = 100000;

    /** System prompt template (null = use default) */
    @Getter
    private String systemPrompt;

    /** Model to use */
    @Getter
    private String model;

    /** Temperature for the model */
    @Getter
    private double temperature = 0.7;

    /** Working directory for file operations. Null until resolved at runtime (see {@link #getWorkingDirectory()}). */
    private String workingDirectory;

    /** Path to the memory file (MEMORY.md). Defaults to <workingDirectory>/.uct8086/MEMORY.md */
    @Getter
    private String memoryFile;

    // ========== Cost / Budget Alerting ==========

    /** Whether to enable cost alerting (warn when budget threshold exceeded) */
    @Getter
    private boolean costAlertEnabled = true;

    /** Warning threshold for session cost in CNY (yuan). Logs WARN when exceeded. Default 5.0 */
    @Getter
    private double sessionCostWarnThreshold = 5.0;

    /** Hard limit for session cost in CNY (yuan). Logs ERROR + throws if exceeded. 0 = disabled */
    @Getter
    private double sessionCostHardLimit = 0.0;

    /** Hard limit for a user's total accumulated cost in CNY (yuan). When exceeded, the
     *  user is circuit-broken (requests rejected). 0 = disabled */
    @Getter
    private double userCostHardLimit = 0.01;

    /** Warning threshold for total accumulated cost across all sessions in CNY. Default 100.0 */
    @Getter
    private double totalCostWarnThreshold = 100.0;

    /** Whether cost circuit breaker is enabled (reject requests when hard limits exceeded) */
    @Getter
    private boolean costBreakerEnabled = true;

    /** Input price per 1M tokens (USD). Overrides TokenUsage default */
    @Getter
    private Double inputPricePerM;

    /** Output price per 1M tokens (USD). Overrides TokenUsage default */
    @Getter
    private Double outputPricePerM;

    // Getters and Setters

    public String getWorkingDirectory() {
        // Resolve lazily so containerized deployments get the correct runtime cwd
        // rather than a value frozen at bean-instantiation time.
        return workingDirectory != null && !workingDirectory.isBlank()
                ? workingDirectory
                : System.getProperty("user.dir");
    }

}
