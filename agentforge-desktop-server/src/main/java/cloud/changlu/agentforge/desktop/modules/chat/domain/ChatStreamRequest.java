package cloud.changlu.agentforge.desktop.modules.chat.domain;

public class ChatStreamRequest {
    private String aid;
    private String sessionId;
    private String message;
    private Long modelId;
    private String projectPath;

    public String getAid() { return aid; }
    public void setAid(String aid) { this.aid = aid; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }
    public String getProjectPath() { return projectPath; }
    public void setProjectPath(String projectPath) { this.projectPath = projectPath; }
}
