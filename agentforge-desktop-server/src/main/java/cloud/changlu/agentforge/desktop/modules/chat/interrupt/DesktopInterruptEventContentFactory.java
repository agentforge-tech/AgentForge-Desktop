package cloud.changlu.agentforge.desktop.modules.chat.interrupt;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentInterruptSnapshot;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentSuspendInfo;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptRequest;
import cloud.changlu.agentforge.desktop.modules.chat.context.DesktopAgentContextAccessor;
import cloud.changlu.agentforge.desktop.modules.chat.context.DesktopExecutionContext;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将 Framework HITL 回调参数转换为桌面端事件内容，对齐上游 {@code AgentInterruptEventContentFactory}。 */
public class DesktopInterruptEventContentFactory {

    private final DesktopAgentContextAccessor contextAccessor;

    public DesktopInterruptEventContentFactory(DesktopAgentContextAccessor contextAccessor) {
        this.contextAccessor = contextAccessor;
    }

    public Map<String, Object> requested(
            AgentSuspendInfo suspendInfo, AgentChatContext chatContext) {
        DesktopExecutionContext context = contextAccessor.requireForHitl(chatContext);
        return content(
                suspendInfo.getBatchId(),
                context.getAiMessageId(),
                "PENDING",
                suspendInfo.getInterrupts());
    }

    public Map<String, Object> snapshot(AgentInterruptSnapshot snapshot, String status) {
        DesktopExecutionContext context = contextAccessor.requireForHitl(snapshot);
        return content(
                snapshot.getBatchId(), context.getAiMessageId(), status, snapshot.getInterrupts());
    }

    private static Map<String, Object> content(
            String batchId, Long messageId, String status, List<InterruptRequest> interrupts) {
        Map<String, Object> content = new LinkedHashMap<String, Object>();
        content.put("batchId", batchId);
        content.put("messageId", messageId);
        content.put("status", status);
        content.put("interrupts", interruptContents(interrupts));
        return content;
    }

    private static List<Map<String, Object>> interruptContents(List<InterruptRequest> interrupts) {
        List<Map<String, Object>> contents = new ArrayList<Map<String, Object>>();
        if (interrupts != null) {
            for (InterruptRequest interrupt : interrupts) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("interruptId", interrupt.getInterruptId());
                item.put("type", interrupt.getType().name());
                item.put("toolRequest", toolRequestContent(interrupt.getToolRequest()));
                item.put("payload", interrupt.getPayload());
                contents.add(item);
            }
        }
        return contents;
    }

    private static Map<String, Object> toolRequestContent(ToolExecutionRequest request) {
        if (request == null) {
            return null;
        }
        Map<String, Object> content = new LinkedHashMap<String, Object>();
        content.put("id", request.id());
        content.put("name", request.name());
        content.put("arguments", request.arguments());
        return content;
    }
}
