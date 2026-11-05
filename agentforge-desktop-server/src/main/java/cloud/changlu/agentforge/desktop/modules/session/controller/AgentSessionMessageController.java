package cloud.changlu.agentforge.desktop.modules.session.controller;

import cloud.changlu.agentforge.desktop.modules.session.domain.SessionIdReq;
import cloud.changlu.agentforge.desktop.modules.session.domain.SessionMessageVo;
import cloud.changlu.agentforge.desktop.modules.session.service.AgentSessionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent/session-messages")
public class AgentSessionMessageController {

    private final AgentSessionService service;

    public AgentSessionMessageController(AgentSessionService service) {
        this.service = service;
    }

    @PostMapping("/detail")
    public List<SessionMessageVo> detail(@RequestBody SessionIdReq req) {
        if (req == null || req.getSessionId() == null) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        return service.listMessages(req.getSessionId());
    }
}
