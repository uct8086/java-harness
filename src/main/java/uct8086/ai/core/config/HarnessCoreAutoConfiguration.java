package uct8086.ai.core.config;

import uct8086.ai.core.tool.HarnessTool;
import uct8086.ai.core.tool.ToolRegistry;
import uct8086.ai.skills.Skill;
import uct8086.ai.skills.SkillRegistry;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;

/**
 * Auto-configuration for the UCT8086-AI core harness.
 * Registers all HarnessTool beans in the ToolRegistry at startup.
 */
@Configuration
@EnableConfigurationProperties(HarnessProperties.class)
public class HarnessCoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(HarnessCoreAutoConfiguration.class);

    private final ToolRegistry toolRegistry;
    private final List<HarnessTool> tools;
    private final SkillRegistry skillRegistry;
    private final HarnessProperties properties;

    public HarnessCoreAutoConfiguration(ToolRegistry toolRegistry, List<HarnessTool> tools,
                                        SkillRegistry skillRegistry, HarnessProperties properties) {
        this.toolRegistry = toolRegistry;
        this.tools = tools;
        this.skillRegistry = skillRegistry;
        this.properties = properties;
    }

    @PostConstruct
    public void registerTools() {
        log.info("Registering {} harness tools...", tools.size());
        for (HarnessTool tool : tools) {
            toolRegistry.register(tool);
        }
        log.info("Harness tools registered: {}", toolRegistry.getToolNames());
    }

    @PostConstruct
    public void loadSkills() {
        // Load skills from the project directory only (<workingDirectory>/.uct8086/skills/).
        // Nothing is read from the user home directory.
        Path baseDir = Path.of(properties.getWorkingDirectory());
        Path projectSkills = baseDir.resolve(".uct8086").resolve("skills");
        skillRegistry.loadFromDirectory(projectSkills);

        log.info("Skills loaded: {} ({} total)", skillRegistry.size() > 0 ? skillRegistry.listSystemSkills().stream().map(Skill::name).toList() : "none", skillRegistry.size());
    }
}
