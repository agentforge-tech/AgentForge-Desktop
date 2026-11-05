package cloud.changlu.agentforge.desktop.modules.session.domain;

public class SessionMessageVo {
    private Long id;
    private Long sessionRefId;
    private String sessionId;
    private Integer seqNum;
    private Integer roleType;
    private Integer status;
    private String startedAt;
    private String finishedAt;
    private Long elapsedMs;
    private Long modelId;
    private String contentText;
    private String events;
    private String createTime;
    private String modifyTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSessionRefId() { return sessionRefId; }
    public void setSessionRefId(Long sessionRefId) { this.sessionRefId = sessionRefId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Integer getSeqNum() { return seqNum; }
    public void setSeqNum(Integer seqNum) { this.seqNum = seqNum; }
    public Integer getRoleType() { return roleType; }
    public void setRoleType(Integer roleType) { this.roleType = roleType; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }
    public String getFinishedAt() { return finishedAt; }
    public void setFinishedAt(String finishedAt) { this.finishedAt = finishedAt; }
    public Long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(Long elapsedMs) { this.elapsedMs = elapsedMs; }
    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }
    public String getContentText() { return contentText; }
    public void setContentText(String contentText) { this.contentText = contentText; }
    public String getEvents() { return events; }
    public void setEvents(String events) { this.events = events; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
    public String getModifyTime() { return modifyTime; }
    public void setModifyTime(String modifyTime) { this.modifyTime = modifyTime; }
}
