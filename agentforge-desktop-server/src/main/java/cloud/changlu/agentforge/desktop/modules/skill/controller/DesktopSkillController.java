package cloud.changlu.agentforge.desktop.modules.skill.controller;

import cloud.changlu.agentforge.desktop.modules.agent.runtime.AgentRuntimeService;
import cloud.changlu.agentforge.desktop.modules.skill.api.SkillMetadata;
import cloud.changlu.agentforge.desktop.modules.skill.domain.SkillImportReq;
import cloud.changlu.agentforge.desktop.modules.skill.api.SkillLoader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping
    public List<SkillMetadata> list() { return loader.listGlobalSkills(); }

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
