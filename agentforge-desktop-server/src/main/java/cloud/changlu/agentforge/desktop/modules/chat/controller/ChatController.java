package cloud.changlu.agentforge.desktop.modules.chat.controller;

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
import cloud.changlu.agentforge.desktop.modules.chat.domain.AgentInterruptResumeReq;
import cloud.changlu.agentforge.desktop.modules.chat.domain.AgentInterruptResponseReq;
import cloud.changlu.agentforge.desktop.modules.chat.domain.AskUserAnswerReq;
import cloud.changlu.agentforge.desktop.modules.chat.interrupt.DesktopInterruptRuntimeRegistry;
import cloud.changlu.agentforge.desktop.modules.chat.domain.ChatStopVo;
import cloud.changlu.agentforge.desktop.modules.chat.domain.ChatStreamRequest;
import cloud.changlu.agentforge.desktop.modules.chat.middleware.ServiceStreamingMiddleware;
import cloud.changlu.agentforge.desktop.modules.chat.middleware.SessionLifecycleMiddleware;
import cloud.changlu.agentforge.desktop.modules.resource.domain.ResolvedResource;
import cloud.changlu.agentforge.desktop.modules.resource.service.DesktopResourceService;
import cloud.changlu.agentforge.desktop.modules.session.dao.AgentSessionMapper;
import cloud.changlu.agentforge.desktop.modules.session.dao.AgentSessionMessageMapper;
import cloud.changlu.agentforge.desktop.modules.session.domain.AgentSessionMessagePojo;
import cloud.changlu.agentforge.desktop.modules.session.domain.AgentSessionPojo;
import cloud.changlu.agentforge.desktop.modules.session.enums.SessionMessageRoleEnum;
import cloud.changlu.agentforge.desktop.modules.session.enums.SessionMessageStatusEnum;
import cloud.changlu.agentforge.desktop.modules.session.enums.SessionStatusEnum;
import cloud.changlu.agentforge.desktop.modules.session.enums.SessionStatusTransfer;
import cloud.changlu.agentforge.desktop.modules.session.service.AgentSessionService;
import cloud.changlu.agentforge.desktop.store.DesktopConfigStore;
import cloud.changlu.agentforge.desktop.modules.workspace.service.WorkspaceBindingService;
import cloud.changlu.agentforge.harness.agent.HarnessAgent;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final DesktopConfigStore configStore;
    private final AgentSessionService sessionService;
    private final AgentRuntimeService runtimeService;
    private final ServiceStreamingMiddleware streamingMiddleware;
    private final SessionLifecycleMiddleware sessionLifecycleMiddleware;
    private final DesktopInterruptRuntimeRegistry interruptRegistry;
    private final AgentSessionMessageMapper messageMapper;
    private final AgentSessionMapper sessionMapper;
    private final DesktopProperties desktopProperties;
    private final WorkspaceBindingService workspaceBindingService;
    private final DesktopResourceService resourceService;
    private final DesktopAgentAttachmentService attachmentService;
    private final DesktopModelCapabilities modelCapabilities;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, CancelHandle> activeStops =
            new ConcurrentHashMap<String, CancelHandle>();

    public ChatController(
            DesktopConfigStore configStore,
            AgentSessionService sessionService,
            AgentRuntimeService runtimeService,
            ServiceStreamingMiddleware streamingMiddleware,
            SessionLifecycleMiddleware sessionLifecycleMiddleware,
            DesktopInterruptRuntimeRegistry interruptRegistry,
            AgentSessionMessageMapper messageMapper,
            AgentSessionMapper sessionMapper,
            DesktopProperties desktopProperties,
            WorkspaceBindingService workspaceBindingService,
            DesktopResourceService resourceService,
            DesktopAgentAttachmentService attachmentService,
            DesktopModelCapabilities modelCapabilities) {
        this.configStore = configStore;
        this.sessionService = sessionService;
        this.runtimeService = runtimeService;
        this.streamingMiddleware = streamingMiddleware;
        this.sessionLifecycleMiddleware = sessionLifecycleMiddleware;
        this.interruptRegistry = interruptRegistry;
        this.messageMapper = messageMapper;
        this.sessionMapper = sessionMapper;
        this.desktopProperties = desktopProperties;
        this.workspaceBindingService = workspaceBindingService;
        this.resourceService = resourceService;
        this.attachmentService = attachmentService;
        this.modelCapabilities = modelCapabilities;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatStreamRequest body) {
        if (body == null || body.getAid() == null || body.getAid().trim().isEmpty()) {
            throw new IllegalArgumentException("aid 不能为空");
        }
        boolean hasMessage = body.getMessage() != null && !body.getMessage().trim().isEmpty();
        if (!hasMessage && !body.hasFiles()) {
            throw new IllegalArgumentException("对话内容或资源不能同时为空");
        }
        String aid = body.getAid().trim();

        configStore.reloadIfChanged();
        DesktopConfigStore.AgentConfig agent = configStore.findAgent(aid);
        if (agent == null) {
            throw new IllegalArgumentException("Agent 不存在或已被删除: " + aid);
        }
        Long modelId = configStore.effectiveModelId(agent, body.getModelId());
        DesktopConfigStore.ModelConfig model = configStore.findModel(modelId);
        if (model == null) {
            throw new IllegalArgumentException("模型不存在或已被删除: " + modelId);
        }

        final AgentSessionService.SessionInitResult init;
        final HarnessAgent runtime;
        final String question;
        final List<AgentAttachment> attachments;
        try {
            List<ResolvedResource> resources = resourceService.resolve(body.getFiles());
            String displayMessage = resourceService.displayText(body.getMessage());
            if (displayMessage.isEmpty() && !resources.isEmpty()) {
                displayMessage = "请处理我提供的本地资源。";
            }
            question = resourceService.buildAgentQuestion(body.getMessage(), resources);
            String reqJson = attachmentService.buildUserReqJson(body, resources, displayMessage);
            attachments = attachmentService.toAgentAttachments(
                    resources,
                    modelCapabilities.supportsImage(model),
                    modelCapabilities.providerCode(model));
            String resolvedSessionId = body.getSessionId() == null || body.getSessionId().trim().isEmpty()
                    ? UUID.randomUUID().toString()
                    : body.getSessionId().trim();
            WorkspaceBindingService.Binding binding = workspaceBindingService.prepare(
                    aid, resolvedSessionId, body.getWorkspaceId(), body.getProjectPath());
            init = sessionService.initChat(
                    aid, resolvedSessionId, displayMessage, modelId, 1L,
                    binding.getWorkspaceId(), binding.getWorkspacePath(), reqJson);
            runtime = runtimeService.getOrCreate(agent, model, binding.getWorkspacePath());
        } catch (RuntimeException e) {
            return notice(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }

        final String executionId = runtimeService.createExecutionId(runtime);
        interruptRegistry.register(
                init.getSessionId(),
                init.getSessionId(),
                init.getSessionPk(),
                init.getAiMessageId(),
                executionId,
                runtime);
        final SseEmitter emitter = new SseEmitter(0L);
        streamingMiddleware.bind(init.getSessionId(), emitter);
        activeStops.put(
                init.getSessionId(),
                execId ->
                        runtimeService.cancel(
                                runtime,
                                init.getSessionId(),
                                execId == null ? executionId : execId));
        emitter.onCompletion(() -> cleanup(init.getSessionId()));
        emitter.onTimeout(() -> cleanup(init.getSessionId()));

        final Map<String, Object> extensions = new HashMap<String, Object>();
        extensions.put(SessionLifecycleMiddleware.EXT_SESSION_ID, init.getSessionId());
        extensions.put(SessionLifecycleMiddleware.EXT_AI_MESSAGE_ID, init.getAiMessageId());
        extensions.put("session.sessionPk", init.getSessionPk());

        executor.execute(
                () -> {
                    try {
                        AgentRequest request =
                                AgentRequest.builder()
                                        .memoryId(init.getSessionId())
                                        .executionId(executionId)
                                        .question(question)
                                        .attachments(attachments)
                                        .extensions(extensions)
                                        .build();
                        runtimeService.stream(runtime, request);
                    } catch (RuntimeException e) {
                        sessionLifecycleMiddleware.finishWithError(init.getSessionId(), e);
                        streamingMiddleware.onError(init.getSessionId(), e);
                    }
                });
        return emitter;
    }

    /** 提交一个 HITL 批次的全部审批结果或 AskUser 回答，并以新的 SSE 续流。 */
    @PostMapping(value = "/interrupts/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resumeInterrupt(@RequestBody AgentInterruptResumeReq body) {
        if (body == null || body.getBatchId() == null || body.getBatchId().trim().isEmpty()) {
            throw new IllegalArgumentException("batchId 不能为空");
        }
        DesktopInterruptRuntimeRegistry.BatchContext pending =
                interruptRegistry.requireBatch(body.getBatchId().trim());
        InterruptBatchResumeCommand command = toResumeCommand(pending, body);
        DesktopInterruptRuntimeRegistry.BatchContext claimed =
                interruptRegistry.claim(body.getBatchId().trim());
        DesktopInterruptRuntimeRegistry.RuntimeContext runtime = claimed.getRuntime();

        final SseEmitter emitter = new SseEmitter(0L);
        streamingMiddleware.bind(runtime.getMemoryId(), emitter);
        activeStops.put(
                runtime.getSessionId(),
                execId ->
                        runtimeService.cancel(
                                runtime.getAgent(),
                                runtime.getSessionId(),
                                execId == null ? runtime.getExecutionId() : execId));
        emitter.onCompletion(() -> cleanup(runtime.getSessionId()));
        emitter.onTimeout(() -> cleanup(runtime.getSessionId()));

        executor.execute(
                () -> {
                    try {
                        runtimeService.resumeInterruptStream(runtime.getAgent(), command);
                    } catch (RuntimeException e) {
                        streamingMiddleware.interruptResumeFailed(
                                runtime.getMemoryId(),
                                claimed.getSuspendInfo().getBatchId(),
                                e);
                        sessionLifecycleMiddleware.finishWithError(runtime.getMemoryId(), e);
                    }
                });
        return emitter;
    }

    /**
     * 停止指定的一轮 AI 回答。
     *
     * <p>可以通过 messageId 精确定位，也可以只传 sessionId 由服务端解析当前活动 AI 消息。
     * 接口不是“发完 cancel 就返回”：先 CAS 标记 CANCELLING，再等待 Agent onStop 收口；
     * 超时后通过条件 UPDATE 兜底为 TIMEOUT_CANCELLED，避免自然完成被覆盖。
     */
    @PostMapping("/stop")
    public ChatStopVo stop(
            @RequestParam(value = "messageId", required = false) Long messageId,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        AgentSessionMessagePojo message = resolveStopMessage(messageId, sessionId);
        if (message == null) {
            return ChatStopVo.of(null, false, null, false);
        }
        AgentSessionPojo session = sessionMapper.selectById(message.getSessionId());
        if (session == null) {
            return ChatStopVo.of(message.getId(), false, statusOf(message), false);
        }
        SessionMessageStatusEnum current = statusOf(message);
        if (current != null && SessionStatusTransfer.isTerminal(current)) {
            return ChatStopVo.of(message.getId(), false, current, false);
        }
        if (current == SessionMessageStatusEnum.WAITING_HITL
                && streamingMiddleware.cancelWaitingInterrupt(session.getSessionId())) {
            activeStops.remove(session.getSessionId());
            AgentSessionMessagePojo canceled = messageMapper.selectById(message.getId());
            return ChatStopVo.of(
                    message.getId(), true, statusOf(canceled), false);
        }

        String now = now();
        boolean firstCanceller = messageMapper.markCancelling(message.getId(), now) > 0;
        boolean cancelRequested = false;
        if (firstCanceller) {
            sessionMapper.updateStatusBySessionId(
                    session.getSessionId(), SessionStatusEnum.CANCELLING.getCode(), now);
            CancelHandle cancel = activeStops.remove(session.getSessionId());
            if (cancel != null) {
                cancel.cancel(message.getExecutionId());
                cancelRequested = true;
            }
        } else {
            SessionMessageStatusEnum latest = statusOf(messageMapper.selectById(message.getId()));
            if (latest != null && SessionStatusTransfer.isTerminal(latest)) {
                return ChatStopVo.of(message.getId(), false, latest, false);
            }
        }

        SessionMessageStatusEnum terminal = awaitTerminal(message.getId());
        if (terminal != null) {
            return ChatStopVo.of(message.getId(), cancelRequested, terminal, false);
        }
        return timeoutCancel(message, session, cancelRequested);
    }

    private AgentSessionMessagePojo resolveStopMessage(Long messageId, String sessionId) {
        AgentSessionMessagePojo message = null;
        if (messageId != null) {
            message = messageMapper.selectById(messageId);
        } else if (sessionId != null && !sessionId.trim().isEmpty()) {
            AgentSessionPojo session = sessionMapper.selectBySessionId(sessionId.trim());
            if (session != null) {
                message = messageMapper.selectActiveAiMessage(session.getId());
            }
        }
        if (message == null) {
            return null;
        }
        if (!SessionMessageRoleEnum.AI.getCode().equals(message.getRoleType())) {
            throw new IllegalArgumentException("messageId 不是 AI 消息");
        }
        return message;
    }

    private SessionMessageStatusEnum awaitTerminal(Long messageId) {
        long timeoutMs = Math.max(1L, desktopProperties.getCancelWaitTimeoutMs());
        long pollMs = Math.max(1L, desktopProperties.getCancelPollIntervalMs());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            SessionMessageStatusEnum current = statusOf(messageMapper.selectById(messageId));
            if (current != null && SessionStatusTransfer.isTerminal(current)) {
                return current;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(pollMs));
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
        }
        return null;
    }

    private ChatStopVo timeoutCancel(
            AgentSessionMessagePojo message,
            AgentSessionPojo session,
            boolean cancelRequested) {
        long finishedMillis = System.currentTimeMillis();
        String finishedAt = formatTime(finishedMillis);
        long elapsedMs = elapsedMillis(message, finishedMillis);
        int updated =
                messageMapper.timeoutCancel(
                        message.getId(),
                        SessionMessageStatusEnum.TIMEOUT_CANCELLED.getCode(),
                        elapsedMs,
                        finishedAt,
                        finishedAt);
        AgentSessionMessagePojo latest = messageMapper.selectById(message.getId());
        SessionMessageStatusEnum status = statusOf(latest);
        if (updated > 0) {
            sessionMapper.updateStatusBySessionId(
                    session.getSessionId(), SessionStatusEnum.FINISHED.getCode(), finishedAt);
            streamingMiddleware.unbind(session.getSessionId());
            return ChatStopVo.of(message.getId(), cancelRequested, status, true);
        }
        return ChatStopVo.of(message.getId(), cancelRequested, status, false);
    }

    private interface CancelHandle {
        void cancel(String executionId);
    }

    private static SessionMessageStatusEnum statusOf(AgentSessionMessagePojo message) {
        return message == null ? null : SessionMessageStatusEnum.getByCode(message.getStatus());
    }

    private static long elapsedMillis(AgentSessionMessagePojo message, long finishedMillis) {
        if (message == null) {
            return 0L;
        }
        String started = message.getStartedAt();
        if (started == null || started.trim().isEmpty()) {
            started = message.getCreateTime();
        }
        if (started == null || started.trim().isEmpty()) {
            return 0L;
        }
        try {
            LocalDateTime start = LocalDateTime.parse(started.trim(), FORMATTER);
            LocalDateTime finish =
                    LocalDateTime.ofInstant(
                            java.time.Instant.ofEpochMilli(finishedMillis),
                            java.time.ZoneId.systemDefault());
            return Math.max(
                    0L,
                    java.time.Duration.between(start, finish).toMillis());
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static String now() {
        return formatTime(System.currentTimeMillis());
    }

    private static String formatTime(long millis) {
        return LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(millis),
                        java.time.ZoneId.systemDefault())
                .format(FORMATTER);
    }

    private static InterruptBatchResumeCommand toResumeCommand(
            DesktopInterruptRuntimeRegistry.BatchContext batch, AgentInterruptResumeReq request) {
        List<AgentInterruptResponseReq> submitted =
                request.getResponses() == null
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
            if (item == null) {
                throw new IllegalArgumentException("缺少中断项回复: " + interrupt.getInterruptId());
            }
            if (interrupt.getType() == InterruptType.PERMISSION_APPROVAL) {
                if (item.getAction() == null) {
                    throw new IllegalArgumentException("权限审核 action 不能为空");
                }
                ApprovalAction action;
                try {
                    action = ApprovalAction.valueOf(item.getAction().trim().toUpperCase());
                } catch (IllegalArgumentException error) {
                    throw new IllegalArgumentException("不支持的审批操作: " + item.getAction());
                }
                responses.add(new InterruptResponse(interrupt.getInterruptId(), action, null));
            } else {
                List<AskUserAnswer> answers = new java.util.ArrayList<AskUserAnswer>();
                if (item.getAnswers() != null) {
                    for (AskUserAnswerReq answer : item.getAnswers()) {
                        answers.add(
                                new AskUserAnswer(
                                        answer.getQuestionId(),
                                        answer.getSelectedOptionIds(),
                                        answer.getCustomValue()));
                    }
                }
                AskUserResponse askUserResponse = new AskUserResponse(answers);
                AskUserSupport.validate(interrupt.getAskUserRequest(), askUserResponse);
                responses.add(
                        InterruptResponse.askUser(interrupt.getInterruptId(), askUserResponse));
            }
        }
        if (!indexed.isEmpty()) {
            throw new IllegalArgumentException("存在未知的 interruptId");
        }
        return new InterruptBatchResumeCommand(request.getBatchId(), responses);
    }

    private SseEmitter notice(String message) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(
                    SseEmitter.event()
                            .data(
                                    cloud.changlu.agentforge.service.protocol.processor.AiResponseProcessor
                                            .respText(message, false)));
            emitter.send(
                    SseEmitter.event()
                            .data(
                                    cloud.changlu.agentforge.service.protocol.processor.AiResponseProcessor
                                            .respEnd(true)));
        } catch (Exception ignored) {
            // client disconnected
        }
        emitter.complete();
        return emitter;
    }

    private void cleanup(String sessionId) {
        activeStops.remove(sessionId);
        streamingMiddleware.unbind(sessionId);
    }
}
