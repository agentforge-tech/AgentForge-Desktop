package cloud.changlu.agentforge.desktop.modules.chat.context;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentInterruptSnapshot;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionMode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 桌面端 Agent 执行上下文的唯一键定义与存取入口，对齐上游 {@code ServiceAgentContextAccessor}。
 *
 * <p>执行入口把归属写进 {@code AgentRequest.extensions}，框架 {@code BaseAgent} 会复制到
 * {@code AgentChatContext.extensions}；HITL 落库时由 Store 转成 {@code snapshot_json.contextAttributes}，
 * 重启恢复时再从快照读回。归属字段缺失即 fail-closed，不允许写入半截快照。
 */
public class DesktopAgentContextAccessor {

    public static final String AI_MESSAGE_ID = "agentforge.execution.aiMessageId";
    public static final String SESSION_ID = "agentforge.execution.sessionId";
    public static final String SESSION_PK = "agentforge.execution.sessionPk";
    public static final String AID = "agentforge.execution.aid";
    public static final String MODEL_ID = "agentforge.execution.modelId";
    public static final String WORKSPACE_PATH = "agentforge.execution.workspacePath";
    public static final String PERMISSION_MODE = "agentforge.execution.permissionMode";

    public void write(Map<String, Object> extensions, DesktopExecutionContext value) {
        if (extensions == null || value == null) {
            return;
        }
        extensions.put(AI_MESSAGE_ID, value.getAiMessageId());
        extensions.put(SESSION_ID, value.getSessionId());
        extensions.put(SESSION_PK, value.getSessionPk());
        extensions.put(AID, value.getAid());
        extensions.put(MODEL_ID, value.getModelId());
        extensions.put(WORKSPACE_PATH, value.getWorkspacePath());
        extensions.put(PERMISSION_MODE, value.getPermissionMode());
    }

    public DesktopExecutionContext read(AgentChatContext context) {
        if (context == null) {
            return new DesktopExecutionContext(null, null, null, null, null, null, null);
        }
        return new DesktopExecutionContext(
                toLong(context.getExtension(AI_MESSAGE_ID)),
                toStringValue(context.getExtension(SESSION_ID)),
                toLong(context.getExtension(SESSION_PK)),
                toStringValue(context.getExtension(AID)),
                toLong(context.getExtension(MODEL_ID)),
                toStringValue(context.getExtension(WORKSPACE_PATH)),
                toPermissionMode(context.getExtension(PERMISSION_MODE)));
    }

    public DesktopExecutionContext read(AgentInterruptSnapshot snapshot) {
        if (snapshot == null) {
            return new DesktopExecutionContext(null, null, null, null, null, null, null);
        }
        Map<String, String> values = snapshot.getContextAttributes();
        return new DesktopExecutionContext(
                toLong(values.get(AI_MESSAGE_ID)),
                values.get(SESSION_ID),
                toLong(values.get(SESSION_PK)),
                values.get(AID),
                toLong(values.get(MODEL_ID)),
                values.get(WORKSPACE_PATH),
                toPermissionMode(values.get(PERMISSION_MODE)));
    }

    public DesktopExecutionContext requireForHitl(AgentChatContext context) {
        return requireForHitl(read(context));
    }

    public DesktopExecutionContext requireForHitl(AgentInterruptSnapshot snapshot) {
        return requireForHitl(read(snapshot));
    }

    public Map<String, String> toSnapshotAttributes(DesktopExecutionContext value) {
        DesktopExecutionContext required = requireForHitl(value);
        Map<String, String> result = new LinkedHashMap<String, String>();
        result.put(AI_MESSAGE_ID, String.valueOf(required.getAiMessageId()));
        result.put(SESSION_ID, required.getSessionId());
        result.put(SESSION_PK, String.valueOf(required.getSessionPk()));
        result.put(AID, required.getAid());
        result.put(MODEL_ID, String.valueOf(required.getModelId()));
        result.put(WORKSPACE_PATH, required.getWorkspacePath());
        result.put(PERMISSION_MODE, required.getPermissionMode().name());
        return result;
    }

    private static DesktopExecutionContext requireForHitl(DesktopExecutionContext value) {
        if (value == null) {
            throw new IllegalStateException("HITL_CONTEXT_ATTRIBUTE_MISSING: context");
        }
        require(value.getAiMessageId(), AI_MESSAGE_ID);
        require(value.getSessionId(), SESSION_ID);
        require(value.getSessionPk(), SESSION_PK);
        require(value.getAid(), AID);
        require(value.getModelId(), MODEL_ID);
        require(value.getWorkspacePath(), WORKSPACE_PATH);
        require(value.getPermissionMode(), PERMISSION_MODE);
        return value;
    }

    private static void require(Object value, String key) {
        if (value == null || (value instanceof String && ((String) value).trim().isEmpty())) {
            throw new IllegalStateException("HITL_CONTEXT_ATTRIBUTE_MISSING: " + key);
        }
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException error) {
            throw new IllegalStateException("invalid long context attribute: " + value, error);
        }
    }

    private static PermissionMode toPermissionMode(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof PermissionMode) {
            return (PermissionMode) value;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return PermissionMode.valueOf(text);
        } catch (RuntimeException error) {
            throw new IllegalStateException("invalid permissionMode context attribute: " + value, error);
        }
    }

    private static String toStringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
