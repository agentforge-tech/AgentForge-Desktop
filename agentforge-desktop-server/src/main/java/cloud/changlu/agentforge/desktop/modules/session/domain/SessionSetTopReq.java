package cloud.changlu.agentforge.desktop.modules.session.domain;

public class SessionSetTopReq {
    private Long sessionId;
    private Boolean isTop;
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Boolean getIsTop() { return isTop; }
    public void setIsTop(Boolean isTop) { this.isTop = isTop; }
}
