package cloud.changlu.agentforge.desktop.modules.chat.controller;

import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.desktop.modules.agent.runtime.AgentRuntimeService;
import cloud.changlu.agentforge.desktop.modules.chat.domain.ChatStreamRequest;
import cloud.changlu.agentforge.desktop.modules.chat.middleware.ServiceStreamingMiddleware;
import cloud.changlu.agentforge.desktop.modules.chat.middleware.SessionLifecycleMiddleware;
import cloud.changlu.agentforge.desktop.modules.session.service.AgentSessionService;
import cloud.changlu.agentforge.desktop.store.DesktopConfigStore;
import cloud.changlu.agentforge.harness.agent.HarnessAgent;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final DesktopConfigStore configStore;
    private final AgentSessionService sessionService;
    private final AgentRuntimeService runtimeService;
    private final ServiceStreamingMiddleware streamingMiddleware;
    private final SessionLifecycleMiddleware sessionLifecycleMiddleware;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Runnable> activeStops = new ConcurrentHashMap<String, Runnable>();

    public ChatController(
            DesktopConfigStore configStore,
            AgentSessionService sessionService,
            AgentRuntimeService runtimeService,
            ServiceStreamingMiddleware streamingMiddleware,
            SessionLifecycleMiddleware sessionLifecycleMiddleware) {
        this.configStore = configStore;
        this.sessionService = sessionService;
        this.runtimeService = runtimeService;
        this.streamingMiddleware = streamingMiddleware;
        this.sessionLifecycleMiddleware = sessionLifecycleMiddleware;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatStreamRequest body) {
        if (body == null || body.getAid() == null || body.getAid().trim().isEmpty()) {
            throw new IllegalArgumentException("aid 不能为空");
        }
        if (body.getMessage() == null || body.getMessage().trim().isEmpty()) {
            throw new IllegalArgumentException("对话内容不能为空");
        }
        String message = body.getMessage().trim();
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
        try {
            init = sessionService.initChat(aid, body.getSessionId(), message, modelId, 1L);
            runtime = runtimeService.getOrCreate(agent, model, body.getProjectPath());
        } catch (RuntimeException e) {
            return notice(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }

        final String executionId = runtimeService.createExecutionId(runtime);
        final SseEmitter emitter = new SseEmitter(0L);
        streamingMiddleware.bind(init.getSessionId(), emitter);
        activeStops.put(
                init.getSessionId(),
                () -> runtimeService.cancel(runtime, init.getSessionId(), executionId));
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
                                        .question(message)
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

    @PostMapping("/stop")
    public void stop(@RequestParam("sessionId") String sessionId) {
        Runnable stop = activeStops.remove(sessionId);
        if (stop != null) {
            stop.run();
        }
        streamingMiddleware.unbind(sessionId);
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
