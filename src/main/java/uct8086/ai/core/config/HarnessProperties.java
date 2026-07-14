package uct8086.ai.core.config;

import uct8086.ai.common.enums.PermissionMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the harness.
 * Maps to OpenHarness's multi-layer config system.
 */
@ConfigurationProperties(prefix = "uct8086.ai")
public class HarnessProperties {

    /** Permission mode for the agent */
    private PermissionMode permissionMode = PermissionMode.DEFAULT;

    /** Maximum number of agent loop iterations */
    private int maxTurns = 50;

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
    private String systemPrompt;

    /** Model to use */
    private String model;

    /** Temperature for the model */
    private double temperature = 0.7;

    /** Working directory for file operations */
    private String workingDirectory = System.getProperty("user.dir");

    /** Path to the memory file (MEMORY.md). Defaults to <workingDirectory>/.uct8086/MEMORY.md */
    private String memoryFile;

    // Getters and Setters

    public PermissionMode getPermissionMode() { return permissionMode; }
    public void setPermissionMode(PermissionMode permissionMode) { this.permissionMode = permissionMode; }

    public int getMaxTurns() { return maxTurns; }
    public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }

    public boolean isRetryEnabled() { return retryEnabled; }
    public void setRetryEnabled(boolean retryEnabled) { this.retryEnabled = retryEnabled; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public long getRetryDelayMs() { return retryDelayMs; }
    public void setRetryDelayMs(long retryDelayMs) { this.retryDelayMs = retryDelayMs; }

    public boolean isParallelToolExecution() { return parallelToolExecution; }
    public void setParallelToolExecution(boolean parallelToolExecution) { this.parallelToolExecution = parallelToolExecution; }

    public boolean isContextCompression() { return contextCompression; }
    public void setContextCompression(boolean contextCompression) { this.contextCompression = contextCompression; }

    public int getCompressionThreshold() { return compressionThreshold; }
    public void setCompressionThreshold(int compressionThreshold) { this.compressionThreshold = compressionThreshold; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public String getWorkingDirectory() { return workingDirectory; }
    public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }

    public String getMemoryFile() { return memoryFile; }
    public void setMemoryFile(String memoryFile) { this.memoryFile = memoryFile; }
}
