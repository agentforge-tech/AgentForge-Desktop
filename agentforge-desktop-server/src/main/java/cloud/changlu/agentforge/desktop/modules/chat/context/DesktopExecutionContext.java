package cloud.changlu.agentforge.desktop.modules.chat.context;

import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionMode;

/**
 * 桌面端一次 Agent 执行的归属上下文。
 *
 * <p>只保留重启恢复真正需要的字段；executionId 由快照列 {@code source_execution_id} 承载，
 * memoryId 由快照列 {@code memory_id} 承载，均不重复写入 contextAttributes。
 */
public final class DesktopExecutionContext {

    private final Long aiMessageId;
    private final String sessionId;
    private final Long sessionPk;
    private final String aid;
    private final Long modelId;
    private final String workspacePath;
    private final PermissionMode permissionMode;

    public DesktopExecutionContext(
            Long aiMessageId,
            String sessionId,
            Long sessionPk,
            String aid,
            Long modelId,
            String workspacePath,
            PermissionMode permissionMode) {
        this.aiMessageId = aiMessageId;
        this.sessionId = sessionId;
        this.sessionPk = sessionPk;
        this.aid = aid;
        this.modelId = modelId;
        this.workspacePath = workspacePath;
        this.permissionMode = permissionMode;
    }

    public Long getAiMessageId() {
        return aiMessageId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Long getSessionPk() {
        return sessionPk;
    }

    public String getAid() {
        return aid;
    }

    public Long getModelId() {
        return modelId;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public PermissionMode getPermissionMode() {
        return permissionMode;
    }
}
