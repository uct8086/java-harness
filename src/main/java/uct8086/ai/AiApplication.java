package uct8086.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * UCT8086-AI: Open Agent Harness - Java Implementation
 *
 * <p>A comprehensive AI agent harness built with Spring Boot 4 + Spring AI 2.0,
 * inspired by OpenHarness (https://github.com/HKUDS/OpenHarness).
 *
 * <p>Subsystems:
 * <ul>
 *   <li>engine - Agent Loop (query → stream → tool-call → loop)</li>
 *   <li>tool - Tool Registry & Execution (permission + hooks pipeline)</li>
 *   <li>permission - Multi-level safety modes</li>
 *   <li>hook - PreToolUse/PostToolUse lifecycle hooks</li>
 *   <li>skill - On-demand skill loading (.md files)</li>
 *   <li>memory - Persistent cross-session memory</li>
 *   <li>task - Background task management</li>
 *   <li>coordinator - Multi-agent coordination</li>
 *   <li>mcp - Model Context Protocol client</li>
 *   <li>command - Slash command system</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
public class AiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
