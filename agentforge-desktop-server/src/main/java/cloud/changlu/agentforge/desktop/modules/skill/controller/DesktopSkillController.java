package cloud.changlu.agentforge.desktop.modules.skill.controller;

import cloud.changlu.agentforge.desktop.modules.agent.runtime.AgentRuntimeService;
import cloud.changlu.agentforge.desktop.modules.skill.api.SkillMetadata;
import cloud.changlu.agentforge.desktop.modules.skill.domain.SkillImportReq;
import cloud.changlu.agentforge.desktop.modules.skill.api.SkillLoader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills")
public class DesktopSkillController {
    private final SkillLoader loader;
    private final AgentRuntimeService runtimeService;

    public DesktopSkillController(SkillLoader loader, AgentRuntimeService runtimeService) {
        this.loader = loader;
        this.runtimeService = runtimeService;
    }

    /**
     * Skill 列表。
     *
     * <p>不带 {@code workspacePath} 时只返回全局 Skill（技能管理页沿用）；带上下文时返回
     * 「全局 + 该工作空间」的生效列表，同名以工作空间覆盖全局。目录不可用时降级为全局，
     * 保证输入框的 Skill 面板不会因为工作空间异常整体不可用。
     */
    @GetMapping
    public List<SkillMetadata> list(@RequestParam(required = false) String workspacePath) {
        Path root = resolveWorkspaceRoot(workspacePath);
        return root == null ? loader.listGlobalSkills() : loader.listEffectiveSkills(root);
    }

    private static Path resolveWorkspaceRoot(String workspacePath) {
        if (workspacePath == null || workspacePath.trim().isEmpty()) return null;
        try {
            Path path = Paths.get(workspacePath.trim()).toAbsolutePath().normalize();
            return Files.isDirectory(path) ? path : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @GetMapping("/root")
    public Map<String, String> root() { return Collections.singletonMap("path", loader.ensureGlobalRoot().toString()); }

    @PostMapping("/import")
    public SkillMetadata importSkill(@RequestBody SkillImportReq req) {
        if (req == null || req.getSourcePath() == null || req.getSourcePath().trim().isEmpty()) throw new IllegalArgumentException("请选择 Skill 目录");
        SkillMetadata imported = loader.importGlobalSkill(Paths.get(req.getSourcePath().trim()));
        runtimeService.clearAll();
        return imported;
    }
}
