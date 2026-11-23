package cloud.changlu.agentforge.desktop.modules.chat.service;

import cloud.changlu.agentforge.agent.domain.AgentAttachment;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.ApprovalAction;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptBatchResumeCommand;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptResponse;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptType;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserAnswer;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserResponse;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserSupport;
import cloud.changlu.agentforge.desktop.config.DesktopProperties;
import cloud.changlu.agentforge.desktop.modules.agent.attachment.DesktopAgentAttachmentService;
import cloud.changlu.agentforge.desktop.modules.agent.attachment.DesktopModelCapabilities;
import cloud.changlu.agentforge.desktop.modules.agent.runtime.AgentRuntimeService;
import cloud.changlu.agentforge.desktop.modules.chat.domain.AgentInterruptResponseReq;
import cloud.changlu.agentforge.desktop.modules.chat.domain.AgentInterruptResumeReq;
import cloud.changlu.agentforge.desktop.modules.chat.domain.AgentInterruptResumeVo;
import cloud.changlu.agentforge.desktop.modules.chat.domain.AskUserAnswerReq;
import cloud.changlu.agentforge.desktop.modules.chat.domain.ChatStopVo;
import cloud.changlu.agentforge.desktop.modules.chat.domain.ChatStreamRequest;
import cloud.changlu.agentforge.desktop.modules.chat.domain.ChatTaskSubmitVo;
import cloud.changlu.agentforge.desktop.modules.chat.event.store.MessageEventFactory;
import cloud.changlu.agentforge.desktop.modules.chat.governance.DesktopPermissionModule;
import cloud.changlu.agentforge.desktop.modules.chat.interrupt.DesktopInterruptRuntimeRegistry;
import cloud.changlu.agentforge.desktop.modules.resource.domain.ResolvedResource;
import cloud.changlu.agentforge.desktop.modules.resource.service.DesktopResourceService;
import cloud.changlu.agentforge.desktop.modules.chat.middleware.ServiceStreamingMiddleware;
import cloud.changlu.agentforge.desktop.modules.chat.middleware.SessionLifecycleMiddleware;
import cloud.changlu.agentforge.desktop.modules.session.dao.AgentSessionMapper;
import cloud.changlu.agentforge.desktop.modules.session.dao.AgentSessionMessageMapper;
import cloud.changlu.agentforge.desktop.modules.session.domain.AgentSessionMessagePojo;
import cloud.changlu.agentforge.desktop.modules.session.domain.AgentSessionPojo;
import cloud.changlu.agentforge.desktop.modules.session.enums.SessionMessageRoleEnum;
import cloud.changlu.agentforge.desktop.modules.session.enums.SessionMessageStatusEnum;
import cloud.changlu.agentforge.desktop.modules.session.enums.SessionStatusEnum;
import cloud.changlu.agentforge.desktop.modules.session.enums.SessionStatusTransfer;
import cloud.changlu.agentforge.desktop.modules.session.service.AgentSessionService;
import cloud.changlu.agentforge.desktop.modules.workspace.service.WorkspaceBindingService;
import cloud.changlu.agentforge.desktop.store.DesktopConfigStore;
import cloud.changlu.agentforge.harness.agent.HarnessAgent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

@Service
public class ChatTaskServiceImpl implements ChatTaskService {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DesktopConfigStore configStore;
    private final AgentSessionService sessionService;
    private final AgentRuntimeService runtimeService;
    private final ServiceStreamingMiddleware streamingMiddleware;
    private final SessionLifecycleMiddleware lifecycleMiddleware;
    private final DesktopInterruptRuntimeRegistry interruptRegistry;
    private final MessageEventFactory eventFactory;
    private final MessageEventSnapshotService snapshotService;
    private final AgentSessionMessageMapper messageMapper;
    private final AgentSessionMapper sessionMapper;
    private final DesktopProperties properties;
    private final WorkspaceBindingService workspaceBindingService;
    private final DesktopResourceService resourceService;
    private final DesktopAgentAttachmentService attachmentService;
    private final DesktopModelCapabilities modelCapabilities;
    private final Executor executor;
    private final Map<Long, Runnable> activeStops = new ConcurrentHashMap<Long, Runnable>();

    public ChatTaskServiceImpl(
            DesktopConfigStore configStore,
            AgentSessionService sessionService,
            AgentRuntimeService runtimeService,
            ServiceStreamingMiddleware streamingMiddleware,
            SessionLifecycleMiddleware lifecycleMiddleware,
            DesktopInterruptRuntimeRegistry interruptRegistry,
            MessageEventFactory eventFactory,
            MessageEventSnapshotService snapshotService,
            AgentSessionMessageMapper messageMapper,
            AgentSessionMapper sessionMapper,
            DesktopProperties properties,
            WorkspaceBindingService workspaceBindingService,
            DesktopResourceService resourceService,
            DesktopAgentAttachmentService attachmentService,
            DesktopModelCapabilities modelCapabilities,
            @Qualifier("chatTaskExecutor") Executor executor) {
        this.configStore = configStore;
        this.sessionService = sessionService;
        this.runtimeService = runtimeService;
        this.streamingMiddleware = streamingMiddleware;
        this.lifecycleMiddleware = lifecycleMiddleware;
        this.interruptRegistry = interruptRegistry;
        this.eventFactory = eventFactory;
        this.snapshotService = snapshotService;
        this.messageMapper = messageMapper;
        this.sessionMapper = sessionMapper;
        this.properties = properties;
        this.workspaceBindingService = workspaceBindingService;
        this.resourceService = resourceService;
        this.attachmentService = attachmentService;
        this.modelCapabilities = modelCapabilities;
        this.executor = executor;
    }

    @Override
    public ChatTaskSubmitVo submit(ChatStreamRequest body) {
        validate(body);
        configStore.reloadIfChanged();
        DesktopConfigStore.AgentConfig agent = configStore.findAgent(body.getAid().trim());
        if (agent == null) {
            throw new IllegalArgumentException("Agent 不存在或已被删除: " + body.getAid());
        }
        Long modelId = configStore.effectiveModelId(agent, body.getModelId());
        DesktopConfigStore.ModelConfig model = configStore.findModel(modelId);
        if (model == null) {
            throw new IllegalArgumentException("模型不存在或已被删除: " + modelId);
        }

        List<ResolvedResource> resources = resourceService.resolve(body.getFiles());
        String displayMessage = resourceService.displayText(body.getMessage());
        if (displayMessage.isEmpty() && !resources.isEmpty()) {
            displayMessage = "请处理我提供的本地资源。";
        }
        String question = resourceService.buildAgentQuestion(body.getMessage(), resources);
        String reqJson = attachmentService.buildUserReqJson(body, resources, displayMessage);
        List<AgentAttachment> attachments = attachmentService.toAgentAttachments(
                resources, modelCapabilities.supportsImage(model), modelCapabilities.providerCode(model));

        String sessionId = body.getSessionId() == null || body.getSessionId().trim().isEmpty()
                ? UUID.randomUUID().toString() : body.getSessionId().trim();
        WorkspaceBindingService.Binding binding = workspaceBindingService.prepare(
                body.getAid().trim(), sessionId, body.getWorkspaceId(), body.getProjectPath());
        AgentSessionService.SessionInitResult init = sessionService.initChat(
                body.getAid().trim(), sessionId, displayMessage, modelId, 1L,
                binding.getWorkspaceId(), binding.getWorkspacePath(), reqJson);
        HarnessAgent runtime = runtimeService.getOrCreate(
                agent, model, binding.getWorkspacePath(),
                DesktopPermissionModule.resolveMode(body.getMode()));
        String executionId = runtimeService.createExecutionId(runtime);
        registerRun(init, runtime, executionId);

        try {
            executor.execute(() -> start(runtime, init, question, executionId, attachments));
        } catch (RuntimeException e) {
            activeStops.remove(init.getAiMessageId());
            streamingMiddleware.onError(init.getSessionId(), e);
            lifecycleMiddleware.finishWithError(init.getSessionId(), e);
            finishTask(init.getAiMessageId());
            throw e;
        }
        return toSubmitVo(init);
    }

    @Override
    public ChatStopVo stop(Long messageId) {
        AgentSessionMessagePojo message = requireOwnedAiMessage(messageId);
        SessionMessageStatusEnum current = statusOf(message);
        if (SessionStatusTransfer.isTerminal(current)) {
            return ChatStopVo.of(messageId, false, current, false);
        }

        if (current == SessionMessageStatusEnum.WAITING_HITL
                && streamingMiddleware.cancelWaitingInterrupt(message.getSessionId())) {
            activeStops.remove(messageId);
            return ChatStopVo.of(messageId, true, SessionMessageStatusEnum.CANCELLED, false);
        }

        boolean firstCanceller = messageMapper.markCancelling(messageId, now()) > 0;
        boolean cancelRequested = false;
        if (firstCanceller) {
            sessionMapper.updateStatus(
                    message.getSessionId(), SessionStatusEnum.CANCELLING.getCode(), now());
            Runnable stop = activeStops.remove(messageId);
            if (stop != null) {
                stop.run();
                cancelRequested = true;
            }
        }

        SessionMessageStatusEnum terminal = awaitTerminal(messageId);
        if (terminal != null) {
            return ChatStopVo.of(messageId, cancelRequested, terminal, false);
        }
        return timeoutCancel(message, cancelRequested);
    }

    @Override
    public AgentInterruptResumeVo resumeInterrupt(AgentInterruptResumeReq request) {
        if (request == null || request.getBatchId() == null
                || request.getBatchId().trim().isEmpty()) {
            throw new IllegalArgumentException("batchId 不能为空");
        }
        DesktopInterruptRuntimeRegistry.BatchContext pending =
                interruptRegistry.requireBatch(request.getBatchId().trim());
        InterruptBatchResumeCommand command = toResumeCommand(pending, request);
        DesktopInterruptRuntimeRegistry.BatchContext claimed =
                interruptRegistry.claim(request.getBatchId().trim());
        DesktopInterruptRuntimeRegistry.RuntimeContext runtime = claimed.getRuntime();
        try {
            executor.execute(() -> resume(claimed, command));
        } catch (RuntimeException e) {
            interruptRegistry.failed(request.getBatchId());
            throw e;
        }
        AgentInterruptResumeVo result = new AgentInterruptResumeVo();
        result.setBatchId(request.getBatchId());
        result.setMessageId(runtime.getAiMessageId());
        result.setStatus(DesktopInterruptRuntimeRegistry.Status.RESUMING.name());
        return result;
    }

    private void registerRun(
            AgentSessionService.SessionInitResult init,
            HarnessAgent runtime,
            String executionId) {
        streamingMiddleware.bind(init.getSessionId(), eventFactory.createSink(init.getAiMessageId()));
        lifecycleMiddleware.registerRun(
                init.getSessionId(), init.getSessionId(), init.getSessionPk(), init.getAiMessageId());
        interruptRegistry.register(
                init.getSessionId(), init.getSessionId(), init.getSessionPk(),
                init.getAiMessageId(), executionId, runtime);
        activeStops.put(
                init.getAiMessageId(),
                () -> runtimeService.cancel(runtime, init.getSessionId(), executionId));
    }

    private void start(
            HarnessAgent runtime,
            AgentSessionService.SessionInitResult init,
            String question,
            String executionId,
            List<AgentAttachment> attachments) {
        Map<String, Object> extensions = new java.util.HashMap<String, Object>();
        extensions.put(SessionLifecycleMiddleware.EXT_SESSION_ID, init.getSessionId());
        extensions.put(SessionLifecycleMiddleware.EXT_AI_MESSAGE_ID, init.getAiMessageId());
        extensions.put("session.sessionPk", init.getSessionPk());
        AgentRequest request = AgentRequest.builder()
                .memoryId(init.getSessionId())
                .executionId(executionId)
                .question(question)
                .attachments(attachments)
                .extensions(extensions)
                .build();
        try {
            runtimeService.stream(runtime, request, () -> finishTask(init.getAiMessageId()));
        } catch (RuntimeException e) {
            streamingMiddleware.onError(init.getSessionId(), e);
            lifecycleMiddleware.finishWithError(init.getSessionId(), e);
            finishTask(init.getAiMessageId());
        }
    }

    private void resume(
            DesktopInterruptRuntimeRegistry.BatchContext batch,
            InterruptBatchResumeCommand command) {
        DesktopInterruptRuntimeRegistry.RuntimeContext runtime = batch.getRuntime();
        Object memoryId = runtime.getMemoryId();
        streamingMiddleware.bind(
                memoryId, eventFactory.createSink(runtime.getAiMessageId()));
        activeStops.put(
                runtime.getAiMessageId(),
                () -> runtimeService.cancel(
                        runtime.getAgent(), runtime.getSessionId(), runtime.getExecutionId()));
        try {
            runtimeService.resumeInterruptStream(
                    runtime.getAgent(), command, () -> finishTask(runtime.getAiMessageId()));
        } catch (RuntimeException e) {
            interruptRegistry.failed(command.getBatchId());
            streamingMiddleware.onError(memoryId, e);
            lifecycleMiddleware.finishWithError(memoryId, e);
            finishTask(runtime.getAiMessageId());
        }
    }

    private void finishTask(Long messageId) {
        activeStops.remove(messageId);
        try {
            snapshotService.saveSnapshot(messageId);
        } catch (RuntimeException ignored) {
            // 订阅端在观察到终态后会再次尝试生成快照。
        }
    }

    private SessionMessageStatusEnum awaitTerminal(Long messageId) {
        long timeoutMs = Math.max(1L, properties.getCancelWaitTimeoutMs());
        long pollMs = Math.max(1L, Math.min(timeoutMs - 1L, properties.getCancelPollIntervalMs()));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            SessionMessageStatusEnum current = statusOf(messageMapper.selectById(messageId));
            if (SessionStatusTransfer.isTerminal(current)) return current;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(pollMs));
            if (Thread.currentThread().isInterrupted()) break;
        }
        return null;
    }

    private ChatStopVo timeoutCancel(
            AgentSessionMessagePojo message, boolean cancelRequested) {
        long finishedMillis = System.currentTimeMillis();
        String finishedAt = format(finishedMillis);
        int updated = messageMapper.timeoutCancel(
                message.getId(), SessionMessageStatusEnum.TIMEOUT_CANCELLED.getCode(),
                elapsedMillis(message, finishedMillis), finishedAt, finishedAt);
        SessionMessageStatusEnum status = statusOf(messageMapper.selectById(message.getId()));
        if (updated > 0) {
            sessionMapper.updateStatus(
                    message.getSessionId(), SessionStatusEnum.FINISHED.getCode(), finishedAt);
            streamingMiddleware.unbind(message.getSessionId());
            return ChatStopVo.of(message.getId(), cancelRequested, status, true);
        }
        return ChatStopVo.of(message.getId(), cancelRequested, status, false);
    }

    private AgentSessionMessagePojo requireOwnedAiMessage(Long messageId) {
        if (messageId == null) throw new IllegalArgumentException("messageId 不能为空");
        AgentSessionMessagePojo message = messageMapper.selectById(messageId);
        if (message == null
                || !SessionMessageRoleEnum.AI.getCode().equals(message.getRoleType())) {
            throw new IllegalArgumentException("AI消息不存在或已被删除");
        }
        sessionService.requireOwned(message.getSessionId());
        return message;
    }

    private static InterruptBatchResumeCommand toResumeCommand(
            DesktopInterruptRuntimeRegistry.BatchContext batch,
            AgentInterruptResumeReq request) {
        List<AgentInterruptResponseReq> submitted = request.getResponses() == null
                ? java.util.Collections.<AgentInterruptResponseReq>emptyList()
                : request.getResponses();
        Map<String, AgentInterruptResponseReq> indexed =
                new java.util.LinkedHashMap<String, AgentInterruptResponseReq>();
        for (AgentInterruptResponseReq item : submitted) {
            if (item == null || item.getInterruptId() == null) {
                throw new IllegalArgumentException("interruptId 不能为空");
            }
            if (indexed.put(item.getInterruptId(), item) != null) {
                throw new IllegalArgumentException("不能重复提交 interruptId");
            }
        }
        List<InterruptResponse> responses = new java.util.ArrayList<InterruptResponse>();
        for (InterruptRequest interrupt : batch.getSuspendInfo().getInterrupts()) {
            AgentInterruptResponseReq item = indexed.remove(interrupt.getInterruptId());
            if (item == null) throw new IllegalArgumentException("缺少中断项回复: " + interrupt.getInterruptId());
            if (interrupt.getType() == InterruptType.PERMISSION_APPROVAL) {
                if (item.getAction() == null) throw new IllegalArgumentException("权限审核 action 不能为空");
                ApprovalAction action;
                try {
                    action = ApprovalAction.valueOf(item.getAction().trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("不支持的审批操作: " + item.getAction());
                }
                responses.add(new InterruptResponse(interrupt.getInterruptId(), action, null));
            } else {
                List<AskUserAnswer> answers = new java.util.ArrayList<AskUserAnswer>();
                if (item.getAnswers() != null) {
                    for (AskUserAnswerReq answer : item.getAnswers()) {
                        answers.add(new AskUserAnswer(
                                answer.getQuestionId(), answer.getSelectedOptionIds(), answer.getCustomValue()));
                    }
                }
                AskUserResponse response = new AskUserResponse(answers);
                AskUserSupport.validate(interrupt.getAskUserRequest(), response);
                responses.add(InterruptResponse.askUser(interrupt.getInterruptId(), response));
            }
        }
        if (!indexed.isEmpty()) throw new IllegalArgumentException("存在未知的 interruptId");
        return new InterruptBatchResumeCommand(request.getBatchId(), responses);
    }

    private static ChatTaskSubmitVo toSubmitVo(AgentSessionService.SessionInitResult init) {
        ChatTaskSubmitVo result = new ChatTaskSubmitVo();
        result.setSessionId(init.getSessionId());
        result.setSessionPk(init.getSessionPk());
        result.setUserMessageId(init.getUserMessageId());
        result.setMessageId(init.getAiMessageId());
        result.setStatus(SessionMessageStatusEnum.CREATED.getCode());
        result.setTitle(init.getTitle());
        return result;
    }

    private static void validate(ChatStreamRequest request) {
        if (request == null || request.getAid() == null || request.getAid().trim().isEmpty()) {
            throw new IllegalArgumentException("aid 不能为空");
        }
        boolean hasMessage = request.getMessage() != null && !request.getMessage().trim().isEmpty();
        if (!hasMessage && !request.hasFiles()) {
            throw new IllegalArgumentException("对话内容或资源不能同时为空");
        }
    }

    private static SessionMessageStatusEnum statusOf(AgentSessionMessagePojo message) {
        return message == null ? null : SessionMessageStatusEnum.getByCode(message.getStatus());
    }

    private static long elapsedMillis(AgentSessionMessagePojo message, long finishedMillis) {
        String started = message.getStartedAt();
        if (started == null || started.trim().isEmpty()) started = message.getCreateTime();
        if (started == null || started.trim().isEmpty()) return 0L;
        try {
            LocalDateTime start = LocalDateTime.parse(started.trim(), FORMATTER);
            LocalDateTime finish = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(finishedMillis), ZoneId.systemDefault());
            return Math.max(0L, Duration.between(start, finish).toMillis());
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static String now() { return format(System.currentTimeMillis()); }

    private static String format(long millis) {
        return LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(FORMATTER);
    }
}
