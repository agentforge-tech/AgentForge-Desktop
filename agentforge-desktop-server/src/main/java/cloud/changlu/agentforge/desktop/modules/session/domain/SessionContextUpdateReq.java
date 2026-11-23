package cloud.changlu.agentforge.desktop.modules.session.domain;

import com.fasterxml.jackson.databind.JsonNode;

public class SessionContextUpdateReq {
    private Long sessionId;
    private JsonNode context;

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public JsonNode getContext() { return context; }
    public void setContext(JsonNode context) { this.context = context; }
}
