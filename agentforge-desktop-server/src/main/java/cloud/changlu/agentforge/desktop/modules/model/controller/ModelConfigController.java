package cloud.changlu.agentforge.desktop.modules.model.controller;

import cloud.changlu.agentforge.desktop.modules.model.domain.ModelConfigOptionsVo;
import cloud.changlu.agentforge.desktop.modules.model.domain.ModelConfigTestReq;
import cloud.changlu.agentforge.desktop.modules.model.domain.ModelConfigTestVo;
import cloud.changlu.agentforge.desktop.modules.model.domain.ModelConfigUpsertReq;
import cloud.changlu.agentforge.desktop.modules.model.domain.ModelConfigVo;
import cloud.changlu.agentforge.desktop.modules.model.service.ModelConfigService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/model-config")
public class ModelConfigController {

    private final ModelConfigService service;

    public ModelConfigController(ModelConfigService service) {
        this.service = service;
    }

    @GetMapping("/options")
    public ModelConfigOptionsVo options() {
        return service.options();
    }

    @GetMapping("/list")
    public List<ModelConfigVo> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ModelConfigVo get(@PathVariable("id") Long id) {
        return service.get(id);
    }

    @PostMapping("/upsert")
    public ModelConfigVo upsert(@RequestBody ModelConfigUpsertReq req) {
        return service.upsert(req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") Long id) {
        service.delete(id);
    }

    @PostMapping("/testConn")
    public ModelConfigTestVo testConn(@RequestBody ModelConfigTestReq req) {
        return service.testConnectivity(req);
    }

    @PostMapping("/testFunctionCall")
    public ModelConfigTestVo testFunctionCall(@RequestBody ModelConfigTestReq req) {
        return service.testFunctionCall(req);
    }
}
