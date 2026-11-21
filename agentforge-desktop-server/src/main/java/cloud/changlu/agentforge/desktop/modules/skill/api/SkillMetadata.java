package cloud.changlu.agentforge.desktop.modules.skill.api;

/** Transport-neutral metadata for a discovered Skill. */
public class SkillMetadata {
    private final String name;
    private final String description;
    private final String scope;
    private final String path;
    private final String skillFile;
    private final long modifyTime;

    public SkillMetadata(String name, String description, String scope, String path, String skillFile, long modifyTime) {
        this.name = name;
        this.description = description;
        this.scope = scope;
        this.path = path;
        this.skillFile = skillFile;
        this.modifyTime = modifyTime;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getScope() { return scope; }
    public String getPath() { return path; }
    public String getSkillFile() { return skillFile; }
    public long getModifyTime() { return modifyTime; }
}
