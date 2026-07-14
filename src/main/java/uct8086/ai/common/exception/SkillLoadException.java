package uct8086.ai.common.exception;

/**
 * Thrown when a skill fails to load.
 */
public class SkillLoadException extends Uct8086Exception {

    private final String skillName;

    public SkillLoadException(String skillName, String message) {
        super("Failed to load skill '" + skillName + "': " + message);
        this.skillName = skillName;
    }

    public SkillLoadException(String skillName, String message, Throwable cause) {
        super("Failed to load skill '" + skillName + "': " + message, cause);
        this.skillName = skillName;
    }

    public String getSkillName() {
        return skillName;
    }
}
