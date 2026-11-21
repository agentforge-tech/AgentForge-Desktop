package cloud.changlu.agentforge.desktop.modules.skill.tool;

import cloud.changlu.agentforge.desktop.modules.skill.api.SkillLoader;
import cloud.changlu.agentforge.model.tool.P;
import cloud.changlu.agentforge.model.tool.Tool;

import java.nio.file.Path;

public final class DesktopSkillTool {
    private final SkillLoader loader;
    private final Path workspaceRoot;

    public DesktopSkillTool(SkillLoader loader, Path workspaceRoot) {
        this.loader = loader;
        this.workspaceRoot = workspaceRoot;
    }

    @Tool(name = "skill", value = "Read the complete SKILL.md for one Skill path from the injected Skill index")
    public String skill(@P("Skill directory or SKILL.md path from the injected Skill index") String path) {
        return loader.skill(workspaceRoot, path);
    }
}
