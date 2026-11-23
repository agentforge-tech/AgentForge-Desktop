package cloud.changlu.agentforge.desktop.modules.session.domain;

public class AgentSessionPojo {
    private Long id;
    private String sessionId;
    private String aid;
    private String title;
    private Integer status;
    private Long modelId;
    private Long workspaceId;
    private String workspacePath;
    private String context;
    private Integer pinOrder;
    private Long createUserId;
    private Integer isDeleted;
    private String createTime;
    private String modifyTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getAid() { return aid; }
    public void setAid(String aid) { this.aid = aid; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
    public String getWorkspacePath() { return workspacePath; }
    public void setWorkspacePath(String workspacePath) { this.workspacePath = workspacePath; }
    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }
    public Integer getPinOrder() { return pinOrder; }
    public void setPinOrder(Integer pinOrder) { this.pinOrder = pinOrder; }
    public Long getCreateUserId() { return createUserId; }
    public void setCreateUserId(Long createUserId) { this.createUserId = createUserId; }
    public Integer getIsDeleted() { return isDeleted; }
    public void setIsDeleted(Integer isDeleted) { this.isDeleted = isDeleted; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
    public String getModifyTime() { return modifyTime; }
    public void setModifyTime(String modifyTime) { this.modifyTime = modifyTime; }
}
