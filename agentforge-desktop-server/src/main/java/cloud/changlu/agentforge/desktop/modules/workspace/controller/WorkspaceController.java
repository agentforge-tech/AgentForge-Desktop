package cloud.changlu.agentforge.desktop.modules.workspace.controller;

import cloud.changlu.agentforge.desktop.modules.workspace.domain.WorkspaceCreateReq;
import cloud.changlu.agentforge.desktop.modules.workspace.domain.WorkspaceImportReq;
import cloud.changlu.agentforge.desktop.modules.workspace.domain.WorkspacePojo;
import cloud.changlu.agentforge.desktop.modules.workspace.domain.WorkspaceRenameReq;
import cloud.changlu.agentforge.desktop.modules.workspace.service.WorkspaceService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {
    private final WorkspaceService service;
    public WorkspaceController(WorkspaceService service) { this.service = service; }

    @GetMapping
    public List<WorkspacePojo> list() { return service.list(); }

    @PostMapping
    public WorkspacePojo create(@RequestBody WorkspaceCreateReq req) {
        if (req == null) throw new IllegalArgumentException("请求体不能为空");
        return service.create(req.getName(), req.getRemark());
    }

    @PostMapping("/import")
    public WorkspacePojo importFolder(@RequestBody WorkspaceImportReq req) {
        if (req == null) throw new IllegalArgumentException("请求体不能为空");
        return service.importFolder(req.getName(), req.getFolderPath());
    }

    @PutMapping("/{id}/name")
    public WorkspacePojo rename(@PathVariable Long id, @RequestBody WorkspaceRenameReq req) {
        if (req == null) throw new IllegalArgumentException("请求体不能为空");
        return service.rename(id, req.getName());
    }

    @DeleteMapping("/{id}")
    public boolean delete(@PathVariable Long id) {
        service.delete(id);
        return true;
    }
}
