package cloud.changlu.agentforge.desktop.modules.chat.interrupt;

import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentSuspendInfo;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptRequest;
import cloud.changlu.agentforge.harness.agent.HarnessAgent;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Desktop HITL 运行期注册表。
 *
 * <p>与 Framework 的内存中断快照同生命周期：记录当前会话绑定的 HarnessAgent 与会话消息上下文，
 * 使审批/AskUser 提交后能在同一 RunState 上恢复。进程重启后不尝试伪恢复，未完成批次按过期处理。
 */
public class DesktopInterruptRuntimeRegistry {

    public enum Status {
        PENDING,
        RESUMING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private final Map<Object, RuntimeContext> runtimes =
            new ConcurrentHashMap<Object, RuntimeContext>();
    private final Map<String, BatchContext> batches = new ConcurrentHashMap<String, BatchContext>();

    public void register(
            Object memoryId,
            String sessionId,
            Long sessionPk,
            Long aiMessageId,
            String executionId,
            HarnessAgent agent) {
        runtimes.put(
                memoryId,
                new RuntimeContext(
                        memoryId, sessionId, sessionPk, aiMessageId, executionId, agent));
    }

    public BatchContext suspend(Object memoryId, AgentSuspendInfo suspendInfo) {
        if (memoryId == null || suspendInfo == null) {
            throw new IllegalArgumentException("memoryId and suspendInfo must not be null");
        }
        RuntimeContext runtime = requireRuntime(memoryId);
        BatchContext created = new BatchContext(runtime, suspendInfo);
        BatchContext previous = batches.putIfAbsent(suspendInfo.getBatchId(), created);
        return previous == null ? created : previous;
    }

    public BatchContext claim(String batchId) {
        BatchContext batch = requireBatch(batchId);
        if (!batch.status.compareAndSet(Status.PENDING, Status.RESUMING)) {
            throw new IllegalStateException("BATCH_ALREADY_CONSUMED: " + batch.status.get());
        }
        return batch;
    }

    public BatchContext requireBatch(String batchId) {
        if (batchId == null || batchId.trim().isEmpty()) {
            throw new IllegalArgumentException("batchId 不能为空");
        }
        BatchContext batch = batches.get(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("INTERRUPT_BATCH_NOT_FOUND");
        }
        return batch;
    }

    public void completed(String batchId) {
        update(batchId, Status.COMPLETED);
    }

    public void failed(String batchId) {
        update(batchId, Status.FAILED);
    }

    public BatchContext cancelByMemoryId(Object memoryId) {
        for (BatchContext batch : batches.values()) {
            if (batch.runtime.memoryId.equals(memoryId)
                    && batch.status.compareAndSet(Status.PENDING, Status.CANCELLED)) {
                return batch;
            }
        }
        return null;
    }

    public void release(Object memoryId) {
        if (memoryId != null) {
            runtimes.remove(memoryId);
        }
    }

    private void update(String batchId, Status status) {
        BatchContext batch = batchId == null ? null : batches.get(batchId);
        if (batch != null) {
            batch.status.set(status);
        }
    }

    private RuntimeContext requireRuntime(Object memoryId) {
        RuntimeContext runtime = runtimes.get(memoryId);
        if (runtime == null) {
            throw new IllegalStateException("HITL_RUNTIME_NOT_FOUND");
        }
        return runtime;
    }

    public static final class RuntimeContext {
        private final Object memoryId;
        private final String sessionId;
        private final Long sessionPk;
        private final Long aiMessageId;
        private final String executionId;
        private final HarnessAgent agent;

        private RuntimeContext(
                Object memoryId,
                String sessionId,
                Long sessionPk,
                Long aiMessageId,
                String executionId,
                HarnessAgent agent) {
            this.memoryId = memoryId;
            this.sessionId = sessionId;
            this.sessionPk = sessionPk;
            this.aiMessageId = aiMessageId;
            this.executionId = executionId;
            this.agent = agent;
        }

        public Object getMemoryId() {
            return memoryId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public Long getSessionPk() {
            return sessionPk;
        }

        public Long getAiMessageId() {
            return aiMessageId;
        }

        public String getExecutionId() {
            return executionId;
        }

        public HarnessAgent getAgent() {
            return agent;
        }
    }

    public static final class BatchContext {
        private final RuntimeContext runtime;
        private final AgentSuspendInfo suspendInfo;
        private final AtomicReference<Status> status = new AtomicReference<Status>(Status.PENDING);

        private BatchContext(RuntimeContext runtime, AgentSuspendInfo suspendInfo) {
            this.runtime = runtime;
            this.suspendInfo = suspendInfo;
        }

        public RuntimeContext getRuntime() {
            return runtime;
        }

        public AgentSuspendInfo getSuspendInfo() {
            return suspendInfo;
        }

        public Status getStatus() {
            return status.get();
        }

        public Map<String, Object> eventContent() {
            Map<String, Object> content = new LinkedHashMap<String, Object>();
            content.put("batchId", suspendInfo.getBatchId());
            content.put("messageId", runtime.aiMessageId);
            content.put("sessionId", runtime.sessionId);
            content.put("status", status.get().name());
            content.put("interrupts", interruptEventContents());
            return content;
        }

        private List<Map<String, Object>> interruptEventContents() {
            List<Map<String, Object>> contents = new ArrayList<Map<String, Object>>();
            for (InterruptRequest interrupt : suspendInfo.getInterrupts()) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("interruptId", interrupt.getInterruptId());
                item.put("type", interrupt.getType().name());
                item.put("toolRequest", toolRequestContent(interrupt.getToolRequest()));
                item.put("payload", interrupt.getPayload());
                contents.add(item);
            }
            return contents;
        }

        private Map<String, Object> toolRequestContent(ToolExecutionRequest request) {
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
}