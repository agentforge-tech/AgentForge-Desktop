package cloud.changlu.agentforge.desktop.modules.agent.runtime;

import cloud.changlu.agentforge.agent.config.LlmModelConfig;
import cloud.changlu.agentforge.agent.config.LlmModelConfigRegistry;
import cloud.changlu.agentforge.agent.config.LlmModelConfigRegistryApi;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.domain.AgentSettings;
import cloud.changlu.agentforge.agent.extend.memory.window.MessageWindowChatMemoryProvider;
import cloud.changlu.agentforge.agent.extend.middlewares.LoggingIAgentMiddleware;
import cloud.changlu.agentforge.agent.extend.middlewares.append.UserMessageAppendMiddleware;
import cloud.changlu.agentforge.agent.extend.middlewares.append.event.UserMessageAppendEventListener;
import cloud.changlu.agentforge.agent.extend.middlewares.append.service.IUserMessageAppendService;
import cloud.changlu.agentforge.agent.extend.middlewares.read.middleware.ReadArtifactMiddleware;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptBatchResumeCommand;
import cloud.changlu.agentforge.agent.governance.interrupt.spi.AgentInterruptStore;
import cloud.changlu.agentforge.agent.stream.TokenStream;
import cloud.changlu.agentforge.desktop.config.DesktopProperties;
import cloud.changlu.agentforge.desktop.config.DesktopAgentPrompt;
import cloud.changlu.agentforge.desktop.modules.agent.attachment.DesktopModelCapabilities;
import cloud.changlu.agentforge.desktop.modules.chat.governance.DesktopPermissionModule;
import cloud.changlu.agentforge.desktop.modules.chat.middleware.ServiceStreamingMiddleware;
import cloud.changlu.agentforge.desktop.modules.chat.middleware.SessionLifecycleMiddleware;
import cloud.changlu.agentforge.desktop.modules.skill.api.SkillLoader;
import cloud.changlu.agentforge.desktop.modules.resource.read.DesktopReadResourceTool;
import cloud.changlu.agentforge.desktop.modules.skill.tool.DesktopSkillTool;
import cloud.changlu.agentforge.desktop.store.DesktopConfigStore;
import cloud.changlu.agentforge.desktop.store.SqliteChatMemoryStore;
import cloud.changlu.agentforge.harness.agent.HarnessAgent;
import cloud.changlu.agentforge.harness.runtime.local.LocalRuntime;
import cloud.changlu.agentforge.harness.runtime.local.config.LocalRuntimeOptions;
import cloud.changlu.agentforge.harness.runtime.local.workspace.Workspace;
import cloud.changlu.agentforge.model.chat.ChatModel;
import cloud.changlu.agentforge.model.chat.StreamingChatModel;
import cloud.changlu.agentforge.model.registry.LlmFactory;
import cloud.changlu.agentforge.model.registry.config.LlmBasicConfig;
import cloud.changlu.agentforge.model.registry.constant.ChatModelProperties;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentRuntimeService {

    private final DesktopConfigStore configStore;
    private final DesktopProperties properties;
    private final SqliteChatMemoryStore memoryStore;
    private final ServiceStreamingMiddleware streamingMiddleware;
    private final SessionLifecycleMiddleware sessionLifecycleMiddleware;
    private final IUserMessageAppendService appendService;
    private final UserMessageAppendEventListener appendEventListener;
    private final AgentInterruptStore agentInterruptStore;
    private final SkillLoader skillLoader;
    private final DesktopModelCapabilities modelCapabilities;
    private static final LlmModelConfigRegistryApi MODEL_CONFIG_REGISTRY =
            LlmModelConfigRegistry.registry();

    private final Map<String, HarnessAgent> cache = new ConcurrentHashMap<String, HarnessAgent>();
    private volatile long lastRevision = -1L;

    public AgentRuntimeService(
            DesktopConfigStore configStore,
            DesktopProperties properties,
            SqliteChatMemoryStore memoryStore,
            ServiceStreamingMiddleware streamingMiddleware,
            SessionLifecycleMiddleware sessionLifecycleMiddleware,
            IUserMessageAppendService appendService,
            UserMessageAppendEventListener appendEventListener,
            AgentInterruptStore agentInterruptStore,
            SkillLoader skillLoader,
            DesktopModelCapabilities modelCapabilities) {
        this.configStore = configStore;
        this.properties = properties;
        this.memoryStore = memoryStore;
        this.streamingMiddleware = streamingMiddleware;
        this.sessionLifecycleMiddleware = sessionLifecycleMiddleware;
        this.appendService = appendService;
        this.appendEventListener = appendEventListener;
        this.agentInterruptStore = agentInterruptStore;
        this.skillLoader = skillLoader;
        this.modelCapabilities = modelCapabilities;
    }

    public HarnessAgent getOrCreate(
            DesktopConfigStore.AgentConfig agent,
            DesktopConfigStore.ModelConfig model,
            String projectPath) {
        refreshIfNeeded();
        Workspace workspace = resolveWorkspace(agent.aid, projectPath);
        String key = key(agent.aid, model.id, workspace.root());
        HarnessAgent existing = cache.get(key);
        if (existing != null) {
            return existing;
        }
        synchronized (key.intern()) {
            existing = cache.get(key);
            if (existing != null) {
                return existing;
            }
            HarnessAgent created = build(agent, model, workspace);
            cache.put(key, created);
            return created;
        }
    }

    public HarnessAgent warmUp(
            DesktopConfigStore.AgentConfig agent, DesktopConfigStore.ModelConfig model) {
        return getOrCreate(agent, model, null);
    }

    public String builtInSystemPrompt(String aid) {
        return DesktopAgentPrompt.render(null);
    }

    public void stream(HarnessAgent agent, AgentRequest request) {
        agent.runStream(request).ignoreErrors().start();
    }

    /** 后台任务使用的可观察启动入口，允许任务服务在 Agent 收口后清理注册表并生成快照。 */
    public void stream(HarnessAgent agent, AgentRequest request, Runnable finished) {
        TokenStream stream = agent.runStream(request)
                .onCompleteResponse(response -> runQuietly(finished))
                .onError(error -> runQuietly(finished))
                .ignoreErrors();
        stream.start();
    }

    public void resumeInterruptStream(HarnessAgent agent, InterruptBatchResumeCommand command) {
        agent.resumeInterruptStream(command).ignoreErrors().start();
    }

    public void resumeInterruptStream(
            HarnessAgent agent, InterruptBatchResumeCommand command, Runnable finished) {
        TokenStream stream = agent.resumeInterruptStream(command)
                .onCompleteResponse(response -> runQuietly(finished))
                .onError(error -> runQuietly(finished))
                .ignoreErrors();
        stream.start();
    }

    public String createExecutionId(HarnessAgent agent) {
        return agent.createExecutionId();
    }

    public void cancel(HarnessAgent agent, String sessionId, String executionId) {
        agent.cancel(sessionId, executionId);
    }

    public void evict(String aid) {
        if (aid == null) {
            return;
        }
        String prefix = aid + "::";
        cache.entrySet().removeIf(
                entry -> {
                    if (!entry.getKey().startsWith(prefix)) {
                        return false;
                    }
                    close(entry.getValue());
                    return true;
                });
    }

    public void evict(String aid, Long modelId) {
        if (aid == null || modelId == null) {
            return;
        }
        String prefix = aid + "::" + modelId + "::";
        cache.entrySet().removeIf(
                entry -> {
                    if (!entry.getKey().startsWith(prefix)) {
                        return false;
                    }
                    close(entry.getValue());
                    return true;
                });
    }

    public void clearAll() {
        for (HarnessAgent agent : cache.values()) {
            close(agent);
        }
        cache.clear();
    }

    @PreDestroy
    public void shutdown() {
        clearAll();
    }

    public void refreshIfNeeded() {
        configStore.reloadIfChanged();
        long revision = configStore.revision();
        if (revision != lastRevision) {
            clearAll();
            lastRevision = revision;
        }
    }

    private HarnessAgent build(
            DesktopConfigStore.AgentConfig agent,
            DesktopConfigStore.ModelConfig model,
            Workspace workspace) {
//        LlmModelConfig modelAdvancedConfig =
//                MODEL_CONFIG_REGISTRY.getConfig(llmConfig.getUrl(), llmConfig.getModelName());
        ChatModelProperties properties =
                ChatModelProperties.builder().timeout(2 * 60 * 60)
//                        .maxTokens(
//                                toActualTokens(
//                                        chatModelConfig.getMaxOutputKTokens(),
//                                        modelAdvancedConfig.getMaxOutputTokens()))
                        .returnThinking(true).build();
        LlmBasicConfig llm =
                LlmBasicConfig.builder()
                        .provider(model.provider)
                        .url(model.url)
                        .modelName(model.modelName)
                        .apiKey(configStore.effectiveApiKey(model))
                        .props(properties.toProperties())
                        .build();

        int maxMessages = resolveMaxMessages(agent);
        MessageWindowChatMemoryProvider memoryProvider =
                new MessageWindowChatMemoryProvider(maxMessages, memoryStore);

        ChatModel chatModel = LlmFactory.buildChatModel(llm);
        StreamingChatModel streamingChatModel = LlmFactory.buildStreamChatModel(llm);
        LocalRuntime runtime = LocalRuntime.builder()
                .options(LocalRuntimeOptions.builder()
                        // 5分钟超时，避免长时间阻塞 Agent 执行。
                        .commandTimeoutMillis(5 * 60 * 1000)
                        .build())
                .workspace(workspace).build();
        String systemPrompt = DesktopAgentPrompt.render(null, skillLoader.loadSkillIndex(workspace.root()));
        ReadArtifactMiddleware readArtifactMiddleware =
                ReadArtifactMiddleware.builder()
                        .supportsImage(modelCapabilities.supportsImage(model))
                        .requiresInlineImage(modelCapabilities.requiresInlineImage(model))
                        .build();
        try {
            return HarnessAgent.builder()
                    .runtime(runtime)
                    .additionalTool(new DesktopSkillTool(skillLoader, workspace.root()))
                    .additionalTool(new DesktopReadResourceTool())
                    .agentName(agent.name)
                    .systemPrompt(systemPrompt)
                    .chatModel(chatModel)
                    .streamingChatModel(streamingChatModel)
                    .chatMemoryProvider(memoryProvider)
                    .permissionInterruptModule(DesktopPermissionModule.build(agentInterruptStore))
                    .agentSettings(
                            AgentSettings.builder().maxSteps(10000).aiCallRetry(3).build())
                    .middleware(new UserMessageAppendMiddleware(appendService, appendEventListener))
                    .middleware(readArtifactMiddleware)
                    .middleware(new LoggingIAgentMiddleware())
                    .middleware(streamingMiddleware)
                    .middleware(sessionLifecycleMiddleware)
                    .build();
        } catch (RuntimeException e) {
            runtime.close();
            throw e;
        } catch (Error e) {
            runtime.close();
            throw e;
        }
    }

    private Workspace resolveWorkspace(String aid, String projectPath) {
        Path root =
                projectPath == null || projectPath.trim().isEmpty()
                        ? properties.rootPath().resolve("workspace")
                        : java.nio.file.Paths.get(projectPath.trim());
        try {
            if (projectPath == null || projectPath.trim().isEmpty()) {
                Files.createDirectories(root);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to create agent workspace: " + root, e);
        }
        return Workspace.builder().id(aid).root(root).build();
    }

    private static void close(HarnessAgent agent) {
        if (agent != null) {
            agent.close();
        }
    }

    private static void runQuietly(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        try {
            runnable.run();
        } catch (RuntimeException ignored) {
            // 清理动作不能反向影响 Agent 生命周期。
        }
    }

    private static int resolveMaxMessages(DesktopConfigStore.AgentConfig agent) {
        if (agent.agentConfig == null
                || agent.agentConfig.chatMemoryConfig == null
                || agent.agentConfig.chatMemoryConfig.memoryConfig == null) {
            return 1000;
        }
        Matcher matcher =
                Pattern.compile("\"maxMessage\"\\s*:\\s*(\\d+)")
                        .matcher(agent.agentConfig.chatMemoryConfig.memoryConfig);
        if (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group(1));
                return value > 0 ? value : 1000;
            } catch (NumberFormatException ignored) {
                return 1000;
            }
        }
        return 1000;
    }

    private static String key(String aid, Long modelId, Path workspaceRoot) {
        return aid + "::" + modelId + "::" + workspaceRoot;
    }
}
