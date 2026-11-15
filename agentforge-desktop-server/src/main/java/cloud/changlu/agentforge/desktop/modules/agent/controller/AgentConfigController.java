package cloud.changlu.agentforge.desktop.modules.agent.controller;

import cloud.changlu.agentforge.desktop.modules.agent.domain.AgentConfigUpsertReq;
import cloud.changlu.agentforge.desktop.modules.agent.domain.AgentConfigVo;
import cloud.changlu.agentforge.desktop.modules.agent.service.AgentConfigService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent-config")
public class AgentConfigController {

    private final AgentConfigService service;

    public AgentConfigController(AgentConfigService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public List<AgentConfigVo> list() {
        return service.list();
    }

    @GetMapping("/built-in-system-prompt")
    public String builtInSystemPrompt() {
        return service.builtInSystemPrompt();
    }

    @GetMapping("/{aid}")
    public AgentConfigVo get(@PathVariable("aid") String aid) {
        return service.get(aid);
    }

    @PostMapping("/upsert")
    public AgentConfigVo upsert(@RequestBody AgentConfigUpsertReq req) {
        return service.upsert(req);
    }

    @DeleteMapping("/{aid}")
    public void delete(@PathVariable("aid") String aid) {
        service.delete(aid);
    }
}
