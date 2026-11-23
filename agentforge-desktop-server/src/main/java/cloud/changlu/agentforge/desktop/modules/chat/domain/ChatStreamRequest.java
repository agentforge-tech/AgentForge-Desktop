package cloud.changlu.agentforge.desktop.modules.chat.domain;

import cloud.changlu.agentforge.desktop.modules.resource.domain.ResourceRef;

import java.util.List;

public class ChatStreamRequest {
    private String aid;
    private String sessionId;
    private String message;
    private Long modelId;
    private Integer mode;
    private String projectPath;
    private Long workspaceId;
    private List<ResourceRef> files;

    public String getAid() { return aid; }
    public void setAid(String aid) { this.aid = aid; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }
    public Integer getMode() { return mode; }
    public void setMode(Integer mode) { this.mode = mode; }
    public String getProjectPath() { return projectPath; }
    public void setProjectPath(String projectPath) { this.projectPath = projectPath; }
    public Long getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(Long workspaceId) { this.workspaceId = workspaceId; }
    public List<ResourceRef> getFiles() { return files; }
    public void setFiles(List<ResourceRef> files) { this.files = files; }

    public boolean hasFiles() {
        return files != null && !files.isEmpty();
    }
}
