package cloud.changlu.agentforge.desktop.modules.agent.runtime;

import cloud.changlu.agentforge.agent.ReActAgent;
import cloud.changlu.agentforge.agent.domain.AgentRequest;
import cloud.changlu.agentforge.agent.extend.memory.window.MessageWindowChatMemoryProvider;
import cloud.changlu.agentforge.agent.extend.middlewares.LoggingIAgentMiddleware;
import cloud.changlu.agentforge.desktop.modules.chat.middleware.ServiceStreamingMiddleware;
import cloud.changlu.agentforge.desktop.modules.chat.middleware.SessionLifecycleMiddleware;
import cloud.changlu.agentforge.desktop.store.DesktopConfigStore;
import cloud.changlu.agentforge.desktop.store.SqliteChatMemoryStore;
import cloud.changlu.agentforge.model.registry.LlmFactory;
import cloud.changlu.agentforge.model.registry.config.LlmBasicConfig;
import cloud.changlu.agentforge.model.registry.constant.ChatModelProperties;
import cloud.changlu.agentforge.model.tool.execution.ToolService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentRuntimeService {

    private final DesktopConfigStore configStore;
    private final SqliteChatMemoryStore memoryStore;
    private final ServiceStreamingMiddleware streamingMiddleware;
    private final SessionLifecycleMiddleware sessionLifecycleMiddleware;

    private final Map<String, ReActAgent> cache = new ConcurrentHashMap<String, ReActAgent>();
    private volatile long lastRevision = -1L;

    public AgentRuntimeService(
            DesktopConfigStore configStore,
            SqliteChatMemoryStore memoryStore,
            ServiceStreamingMiddleware streamingMiddleware,
            SessionLifecycleMiddleware sessionLifecycleMiddleware) {
        this.configStore = configStore;
        this.memoryStore = memoryStore;
        this.streamingMiddleware = streamingMiddleware;
        this.sessionLifecycleMiddleware = sessionLifecycleMiddleware;
    }

    public ReActAgent getOrCreate(
            DesktopConfigStore.AgentConfig agent, DesktopConfigStore.ModelConfig model) {
        refreshIfNeeded();
        String key = key(agent.aid, model.id);
        ReActAgent existing = cache.get(key);
        if (existing != null) {
            return existing;
        }
        synchronized (key.intern()) {
            existing = cache.get(key);
            if (existing != null) {
                return existing;
            }
            ReActAgent created = build(agent, model);
            cache.put(key, created);
            return created;
        }
    }

    public ReActAgent warmUp(
            DesktopConfigStore.AgentConfig agent, DesktopConfigStore.ModelConfig model) {
        return getOrCreate(agent, model);
    }

    public void stream(ReActAgent agent, AgentRequest request) {
        agent.runStream(request).ignoreErrors().start();
    }

    public void cancel(ReActAgent agent, String sessionId) {
        agent.cancel(sessionId);
    }

    public void evict(String aid) {
        if (aid == null) {
            return;
        }
        String prefix = aid + "::";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public void evict(String aid, Long modelId) {
        if (aid == null || modelId == null) {
            return;
        }
        cache.remove(key(aid, modelId));
    }

    public void clearAll() {
        cache.clear();
    }

    public void refreshIfNeeded() {
        configStore.reloadIfChanged();
        long revision = configStore.revision();
        if (revision != lastRevision) {
            cache.clear();
            lastRevision = revision;
        }
    }

    private ReActAgent build(
            DesktopConfigStore.AgentConfig agent, DesktopConfigStore.ModelConfig model) {
        ChatModelProperties properties =
                ChatModelProperties.builder().timeout(2 * 60 * 60).returnThinking(true).build();
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

        String systemPrompt =
                agent.agentConfig == null ? null : agent.agentConfig.systemPrompt;

        return ReActAgent.builder()
                .agentName(agent.name)
                .systemPrompt(systemPrompt)
                .chatModel(LlmFactory.buildChatModel(llm))
                .streamingChatModel(LlmFactory.buildStreamChatModel(llm))
                .chatMemoryProvider(memoryProvider)
                .middleware(new LoggingIAgentMiddleware())
                .middleware(sessionLifecycleMiddleware)
                .middleware(streamingMiddleware)
                .toolService(new ToolService())
                .build();
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

    private static String key(String aid, Long modelId) {
        return aid + "::" + modelId;
    }
}
