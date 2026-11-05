package cloud.changlu.agentforge.desktop.modules.session.controller;

import cloud.changlu.agentforge.desktop.modules.session.domain.SessionChangeTitleReq;
import cloud.changlu.agentforge.desktop.modules.session.domain.SessionIdReq;
import cloud.changlu.agentforge.desktop.modules.session.domain.SessionListReq;
import cloud.changlu.agentforge.desktop.modules.session.domain.SessionSetTopReq;
import cloud.changlu.agentforge.desktop.modules.session.domain.SessionVo;
import cloud.changlu.agentforge.desktop.modules.session.service.AgentSessionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent/sessions")
public class AgentSessionController {

    private final AgentSessionService service;

    public AgentSessionController(AgentSessionService service) {
        this.service = service;
    }

    @PostMapping("/sessionList")
    public List<SessionVo> sessionList(@RequestBody(required = false) SessionListReq req) {
        SessionListReq query = req == null ? new SessionListReq() : req;
        return service.listNormal(query.getAid(), query.getLimit());
    }

    @PostMapping("/topList")
    public List<SessionVo> topList(@RequestBody(required = false) SessionListReq req) {
        SessionListReq query = req == null ? new SessionListReq() : req;
        return service.listTop(query.getAid());
    }

    @PostMapping("/setTop")
    public Boolean setTop(@RequestBody SessionSetTopReq req) {
        if (req == null || req.getSessionId() == null) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        return service.setTop(req.getSessionId(), Boolean.TRUE.equals(req.getIsTop()));
    }

    @PostMapping("/changeTitle")
    public SessionVo changeTitle(@RequestBody SessionChangeTitleReq req) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        return service.changeTitle(req.getSessionId(), req.getTitle());
    }

    @PostMapping("/delete")
    public Boolean delete(@RequestBody SessionIdReq req) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        return service.delete(req.getSessionId());
    }
}
