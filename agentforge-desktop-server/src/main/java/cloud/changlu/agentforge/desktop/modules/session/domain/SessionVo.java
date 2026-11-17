package cloud.changlu.agentforge.desktop.modules.session.domain;

public class SessionVo {
    private Long id;
    private String sessionId;
    private String aid;
    private String title;
    private Integer status;
    private Long modelId;
    private Long workspaceId;
    private String workspacePath;
    private Integer topOrder;
    private String createTime;
    private String modifyTime;
    private boolean running;
    private int messageCount;
    private String lastMessageAt;

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
    public Integer getTopOrder() { return topOrder; }
    public void setTopOrder(Integer topOrder) { this.topOrder = topOrder; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
    public String getModifyTime() { return modifyTime; }
    public void setModifyTime(String modifyTime) { this.modifyTime = modifyTime; }
    public boolean isRunning() { return running; }
    public void setRunning(boolean running) { this.running = running; }
    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }
    public String getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(String lastMessageAt) { this.lastMessageAt = lastMessageAt; }
}
