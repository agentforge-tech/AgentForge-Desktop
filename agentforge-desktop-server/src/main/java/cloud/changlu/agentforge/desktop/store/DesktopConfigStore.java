package cloud.changlu.agentforge.desktop.store;

import cloud.changlu.agentforge.desktop.config.DesktopProperties;
import cloud.changlu.agentforge.desktop.config.DesktopAgentPrompt;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class DesktopConfigStore {

    private static final Logger log = LoggerFactory.getLogger(DesktopConfigStore.class);
    private static final String BUILTIN_AGENT_AID = "agt_default";
    private static final String BUILTIN_AGENT_NAME = "AgentForge Desktop";

    private final DesktopProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicLong revision = new AtomicLong();

    private RootConfig root;
    private long lastModified = -1L;
    private long lastSize = -1L;
    private long modelsLastModified = -1L;
    private long modelsLastSize = -1L;

    public DesktopConfigStore(DesktopProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        ensureLoaded();
    }

    public synchronized void ensureLoaded() {
        if (root != null) {
            return;
        }
        Path file = properties.configPath();
        Path modelsFile = properties.modelsPath();
        try {
            Files.createDirectories(file.getParent());
            migrateLegacyConfig(file);
            if (!Files.exists(file)) {
                copyDefaultConfig(file);
            }
            root = load(file);
            if (migrateAgentPromptStorage(root)) {
                writeConfig(file, root);
                log.info("desktop Agent prompt storage migrated to user-instructions format");
            }
            boolean legacyModels = !Files.exists(modelsFile) && !root.models.isEmpty();
            if (Files.exists(modelsFile)) {
                root.models = loadModels(modelsFile);
            } else if (legacyModels) {
                writeModels(modelsFile, root.models);
                writeConfig(file, root);
            } else {
                copyDefaultModels(modelsFile);
            }
            refreshStamp(file);
            refreshModelsStamp(modelsFile);
            revision.incrementAndGet();
            log.info("desktop config loaded: {}", file);
            log.info("desktop models loaded: {}", modelsFile);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load desktop config: " + file, e);
        }
    }

    public synchronized boolean reloadIfChanged() {
        ensureLoaded();
        Path file = properties.configPath();
        Path modelsFile = properties.modelsPath();
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            long size = Files.size(file);
            long modelsModified = Files.getLastModifiedTime(modelsFile).toMillis();
            long modelsSize = Files.size(modelsFile);
            if (modified == lastModified
                    && size == lastSize
                    && modelsModified == modelsLastModified
                    && modelsSize == modelsLastSize) {
                return false;
            }
            RootConfig loaded = load(file);
            try {
                loaded.models = loadModels(modelsFile);
            } catch (Exception e) {
                log.warn("failed to reload desktop models, keep previous snapshot: {}", modelsFile, e);
                loaded.models = root.models;
            }
            root = loaded;
            refreshStamp(file);
            refreshModelsStamp(modelsFile);
            revision.incrementAndGet();
            log.info("desktop config reloaded: {} , {}", file, modelsFile);
            return true;
        } catch (Exception e) {
            log.warn("failed to reload desktop config, keep previous snapshot: {}", file, e);
            return false;
        }
    }

    public long revision() {
        ensureLoaded();
        return revision.get();
    }

    public synchronized List<ModelConfig> listModels() {
        ensureLoaded();
        return new ArrayList<ModelConfig>(root.models);
    }

    public synchronized List<AgentConfig> listAgents() {
        ensureLoaded();
        return new ArrayList<AgentConfig>(root.agents);
    }

    public synchronized ModelConfig findModel(Long id) {
        ensureLoaded();
        if (id == null) {
            return null;
        }
        for (ModelConfig model : root.models) {
            if (id.equals(model.id)) {
                return model;
            }
        }
        return null;
    }

    public synchronized AgentConfig findAgent(String aid) {
        ensureLoaded();
        if (aid == null) {
            return null;
        }
        for (AgentConfig agent : root.agents) {
            if (aid.equals(agent.aid)) {
                return agent;
            }
        }
        return null;
    }

    public synchronized List<Long> selectableModelIds(AgentConfig agent) {
        ensureLoaded();
        List<Long> ids = new ArrayList<Long>();
        if (agent != null
                && agent.agentConfig != null
                && agent.agentConfig.chatModel != null
                && agent.agentConfig.chatModel.modelIds != null
                && !agent.agentConfig.chatModel.modelIds.isEmpty()) {
            ids.addAll(agent.agentConfig.chatModel.modelIds);
            return ids;
        }
        for (ModelConfig model : root.models) {
            ids.add(model.id);
        }
        return ids;
    }

    public synchronized Long effectiveModelId(AgentConfig agent, Long requestedModelId) {
        List<Long> selectable = selectableModelIds(agent);
        Long modelId = requestedModelId;
        if (modelId == null && agent != null && agent.agentConfig != null
                && agent.agentConfig.chatModel != null) {
            modelId = agent.agentConfig.chatModel.defaultModelId;
        }
        if (modelId == null && !selectable.isEmpty()) {
            modelId = selectable.get(0);
        }
        if (modelId == null) {
            throw new IllegalArgumentException("没有可用模型，请先在配置中添加模型");
        }
        if (!selectable.contains(modelId)) {
            throw new IllegalArgumentException("模型不在当前 Agent 的可选范围内: " + modelId);
        }
        if (findModel(modelId) == null) {
            throw new IllegalArgumentException("模型不存在或已被删除: " + modelId);
        }
        return modelId;
    }

    public synchronized ModelConfig upsertModel(ModelConfig request) {
        ensureLoaded();
        if (request == null) {
            throw new IllegalArgumentException("模型配置不能为空");
        }
        if (request.name == null || request.name.trim().isEmpty()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        if (request.modelName == null || request.modelName.trim().isEmpty()) {
            throw new IllegalArgumentException("模型标识不能为空");
        }
        if (request.provider == null || (request.provider != 1 && request.provider != 2)) {
            throw new IllegalArgumentException("不支持的模型厂商: " + request.provider);
        }
        if (request.modelType == null) {
            request.modelType = 0;
        }
        String now = now();
        ModelConfig existing = findModel(request.id);
        if (existing == null) {
            request.id = nextModelId();
            request.createTime = now;
            request.modifyTime = now;
            if (request.apiKey == null) {
                request.apiKey = "";
            }
            root.models.add(request);
        } else {
            if (request.apiKey == null || request.apiKey.trim().isEmpty()) {
                request.apiKey = existing.apiKey;
            }
            request.createTime = existing.createTime;
            request.modifyTime = now;
            int index = root.models.indexOf(existing);
            root.models.set(index, request);
        }
        save(root);
        return request;
    }

    public synchronized void deleteModel(Long id) {
        ensureLoaded();
        ModelConfig existing = findModel(id);
        if (existing == null) {
            return;
        }
        for (AgentConfig agent : root.agents) {
            List<Long> ids = selectableModelIds(agent);
            if (ids.contains(id)) {
                throw new IllegalArgumentException("模型正被 Agent 使用，无法删除: " + id);
            }
        }
        root.models.remove(existing);
        save(root);
    }

    public synchronized AgentConfig upsertAgent(AgentConfig request) {
        ensureLoaded();
        if (request == null) {
            throw new IllegalArgumentException("Agent 配置不能为空");
        }
        if (request.name == null || request.name.trim().isEmpty()) {
            throw new IllegalArgumentException("Agent 名称不能为空");
        }
        if (request.agentConfig == null) {
            request.agentConfig = new AgentSettings();
        }
        if (request.agentConfig.chatModel == null) {
            request.agentConfig.chatModel = new ChatModelBinding();
        }
        ChatModelBinding binding = request.agentConfig.chatModel;
        if (binding.modelIds == null) {
            binding.modelIds = new ArrayList<Long>();
        }
        for (Long modelId : binding.modelIds) {
            if (findModel(modelId) == null) {
                throw new IllegalArgumentException("可选模型不存在: " + modelId);
            }
        }
        if (binding.defaultModelId == null && !binding.modelIds.isEmpty()) {
            binding.defaultModelId = binding.modelIds.get(0);
        }
        if (binding.defaultModelId == null) {
            throw new IllegalArgumentException("请选择默认模型");
        }
        if (findModel(binding.defaultModelId) == null) {
            throw new IllegalArgumentException("默认模型不存在: " + binding.defaultModelId);
        }
        if (!binding.modelIds.isEmpty() && !binding.modelIds.contains(binding.defaultModelId)) {
            throw new IllegalArgumentException("默认模型必须包含在可选模型中");
        }
        if (request.agentConfig.chatMemoryConfig == null) {
            request.agentConfig.chatMemoryConfig = new ChatMemorySettings();
        }
        if (request.agentConfig.chatMemoryConfig.type == null) {
            request.agentConfig.chatMemoryConfig.type = 1;
        }
        String now = now();
        AgentConfig existing = findAgent(request.aid);
        if (existing == null) {
            if (request.aid == null || request.aid.trim().isEmpty()) {
                request.aid = newAid();
            }
            request.type = request.type == null ? 1 : request.type;
            request.createTime = now;
            request.modifyTime = now;
            if (request.experConfig == null) {
                request.experConfig = defaultExperConfig();
            }
            root.agents.add(request);
        } else {
            if (request.experConfig == null) {
                request.experConfig = existing.experConfig;
            }
            request.aid = existing.aid;
            request.type = existing.type == null ? 1 : existing.type;
            request.createTime = existing.createTime;
            request.modifyTime = now;
            int index = root.agents.indexOf(existing);
            root.agents.set(index, request);
        }
        if (root.defaultAid == null || findAgent(root.defaultAid) == null) {
            root.defaultAid = root.agents.isEmpty() ? null : root.agents.get(0).aid;
        }
        save(root);
        return request;
    }

    public synchronized void deleteAgent(String aid) {
        ensureLoaded();
        AgentConfig existing = findAgent(aid);
        if (existing == null) {
            return;
        }
        root.agents.remove(existing);
        if (aid.equals(root.defaultAid)) {
            root.defaultAid = root.agents.isEmpty() ? null : root.agents.get(0).aid;
        }
        save(root);
    }

    public String effectiveApiKey(ModelConfig model) {
        if (model == null) {
            return "";
        }
        if (model.apiKey != null && !model.apiKey.trim().isEmpty()) {
            return model.apiKey.trim();
        }
        String env = System.getenv("AGENTFORGE_DESKTOP_MODEL_API_KEY");
        if (env != null && !env.trim().isEmpty()) {
            return env.trim();
        }
        String property = System.getProperty("agentforge.desktop.model-api-key");
        return property == null ? "" : property.trim();
    }

    public String defaultAid() {
        ensureLoaded();
        return root.defaultAid;
    }

    private void save(RootConfig config) {
        normalize(config);
        validate(config);
        Path file = properties.configPath();
        Path modelsFile = properties.modelsPath();
        try {
            Files.createDirectories(file.getParent());
            writeConfig(file, config);
            writeModels(modelsFile, config.models);
            this.root = config;
            refreshStamp(file);
            refreshModelsStamp(modelsFile);
            revision.incrementAndGet();
        } catch (IOException e) {
            throw new IllegalStateException("failed to write desktop config: " + file, e);
        }
    }

    private void writeConfig(Path file, RootConfig config) throws IOException {
        byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(config);
        writeAtomic(file, bytes);
    }

    private void writeModels(Path file, List<ModelConfig> models) throws IOException {
        ModelsConfig config = new ModelsConfig();
        config.models = models == null ? new ArrayList<ModelConfig>() : models;
        byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(config);
        writeAtomic(file, bytes);
    }

    private void writeAtomic(Path file, byte[] bytes) throws IOException {
        Path temp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try (FileChannel channel =
                FileChannel.open(
                        temp,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
        Path backup = file.resolveSibling(file.getFileName().toString() + ".bak");
        if (Files.exists(file)) {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows
        }
    }

    private RootConfig load(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        RootConfig config = objectMapper.readValue(bytes, RootConfig.class);
        if (config == null) {
            throw new IllegalStateException("desktop config is empty");
        }
        normalize(config);
        return config;
    }

    private List<ModelConfig> loadModels(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ModelsConfig config = objectMapper.readValue(bytes, ModelsConfig.class);
        if (config == null || config.models == null) {
            return new ArrayList<ModelConfig>();
        }
        return config.models;
    }

    private void migrateLegacyConfig(Path file) throws IOException {
        Path legacy = properties.legacyConfigPath();
        if (legacy.equals(file)) {
            return;
        }
        if (!Files.exists(file) && Files.exists(legacy)) {
            Files.move(legacy, file);
            log.info("desktop config migrated from legacy path: {} -> {}", legacy, file);
            Path legacyBackup = legacy.resolveSibling(legacy.getFileName().toString() + ".bak");
            Path backup = file.resolveSibling(file.getFileName().toString() + ".bak");
            if (!Files.exists(backup) && Files.exists(legacyBackup)) {
                Files.move(legacyBackup, backup);
            }
        }
    }

    private void copyDefaultConfig(Path file) throws IOException {
        ClassPathResource resource = new ClassPathResource("defaults/config.json");
        if (!resource.exists()) {
            RootConfig empty = new RootConfig();
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(empty);
            Files.write(file, bytes);
            return;
        }
        try (InputStream input = resource.getInputStream()) {
            Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("desktop config initialized from defaults: {}", file);
    }

    private void copyDefaultModels(Path file) throws IOException {
        ClassPathResource resource = new ClassPathResource("defaults/models.json");
        if (!resource.exists()) {
            ModelsConfig empty = new ModelsConfig();
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(empty);
            Files.write(file, bytes);
            return;
        }
        try (InputStream input = resource.getInputStream()) {
            Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("desktop models initialized from defaults: {}", file);
    }

    private void refreshStamp(Path file) throws IOException {
        lastModified = Files.getLastModifiedTime(file).toMillis();
        lastSize = Files.size(file);
    }

    private void refreshModelsStamp(Path file) throws IOException {
        modelsLastModified = Files.getLastModifiedTime(file).toMillis();
        modelsLastSize = Files.size(file);
    }

    private void normalize(RootConfig config) {
        if (config.version == null || config.version < 1) {
            config.version = 1;
        }
        if (config.models == null) {
            config.models = new ArrayList<ModelConfig>();
        }
        if (config.agents == null) {
            config.agents = new ArrayList<AgentConfig>();
        }
        for (ModelConfig model : config.models) {
            if (model.modelType == null) {
                model.modelType = 0;
            }
            if (model.apiKey == null) {
                model.apiKey = "";
            }
            if (model.createTime == null) {
                model.createTime = now();
            }
            if (model.modifyTime == null) {
                model.modifyTime = model.createTime;
            }
        }
        for (AgentConfig agent : config.agents) {
            if (BUILTIN_AGENT_AID.equals(agent.aid)
                    && (agent.name == null
                            || agent.name.trim().isEmpty()
                            || "AgentForge 助手".equals(agent.name)
                            || "默认 Agent".equals(agent.name))) {
                agent.name = BUILTIN_AGENT_NAME;
                agent.description = "AgentForge Desktop 默认 AI 助手";
                if (agent.avatar == null || agent.avatar.trim().isEmpty()) {
                    agent.avatar = "/logo.png";
                }
            }
            if (agent.type == null) {
                agent.type = 1;
            }
            if (agent.agentConfig == null) {
                agent.agentConfig = new AgentSettings();
            }
            if (agent.agentConfig.chatModel == null) {
                agent.agentConfig.chatModel = new ChatModelBinding();
            }
            if (agent.agentConfig.chatModel.modelIds == null) {
                agent.agentConfig.chatModel.modelIds = new ArrayList<Long>();
            }
            if (agent.agentConfig.chatModel.defaultModelId == null
                    && !agent.agentConfig.chatModel.modelIds.isEmpty()) {
                agent.agentConfig.chatModel.defaultModelId =
                        agent.agentConfig.chatModel.modelIds.get(0);
            }
            if (agent.agentConfig.chatMemoryConfig == null) {
                agent.agentConfig.chatMemoryConfig = new ChatMemorySettings();
            }
            if (agent.agentConfig.chatMemoryConfig.type == null) {
                agent.agentConfig.chatMemoryConfig.type = 1;
            }
            if (agent.experConfig == null) {
                agent.experConfig = defaultExperConfig();
            }
            if (agent.createTime == null) {
                agent.createTime = now();
            }
            if (agent.modifyTime == null) {
                agent.modifyTime = agent.createTime;
            }
        }
        if (config.defaultAid == null && !config.agents.isEmpty()) {
            config.defaultAid = config.agents.get(0).aid;
        }
    }

    private boolean migrateAgentPromptStorage(RootConfig config) {
        boolean changed = false;
        for (AgentConfig agent : config.agents) {
            if (agent.agentConfig != null) {
                String normalized =
                        DesktopAgentPrompt.normalizeUserInstructions(
                                agent.agentConfig.systemPrompt);
                if (normalized.equals(agent.agentConfig.systemPrompt)) {
                    continue;
                }
                agent.agentConfig.systemPrompt = normalized;
                agent.modifyTime = now();
                changed = true;
            }
        }
        return changed;
    }

    private void validate(RootConfig config) {
        Set<Long> modelIds = new HashSet<Long>();
        for (ModelConfig model : config.models) {
            if (model.id == null || !modelIds.add(model.id)) {
                throw new IllegalArgumentException("模型 id 重复或为空");
            }
        }
        Set<String> aids = new HashSet<String>();
        for (AgentConfig agent : config.agents) {
            if (agent.aid == null || agent.aid.trim().isEmpty() || !aids.add(agent.aid)) {
                throw new IllegalArgumentException("Agent aid 重复或为空");
            }
            if (agent.name == null || agent.name.trim().isEmpty()) {
                throw new IllegalArgumentException("Agent 名称不能为空");
            }
            if (agent.agentConfig == null || agent.agentConfig.chatModel == null) {
                throw new IllegalArgumentException("Agent 模型配置不能为空");
            }
            Long defaultModelId = agent.agentConfig.chatModel.defaultModelId;
            if (defaultModelId != null && findModelIn(config, defaultModelId) == null) {
                throw new IllegalArgumentException("默认模型不存在: " + defaultModelId);
            }
            for (Long id : agent.agentConfig.chatModel.modelIds) {
                if (findModelIn(config, id) == null) {
                    throw new IllegalArgumentException("可选模型不存在: " + id);
                }
            }
        }
    }

    private static ModelConfig findModelIn(RootConfig config, Long id) {
        if (id == null) {
            return null;
        }
        for (ModelConfig model : config.models) {
            if (id.equals(model.id)) {
                return model;
            }
        }
        return null;
    }

    private Long nextModelId() {
        long max = 0L;
        for (ModelConfig model : root.models) {
            if (model.id != null && model.id > max) {
                max = model.id;
            }
        }
        return max + 1L;
    }

    private static String newAid() {
        return "agt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static Map<String, Object> defaultExperConfig() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("welcomeText", "今天想解决什么问题？");
        map.put("themeColor", "#4f46e5");
        return map;
    }

    private static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public static class RootConfig {
        public Integer version = 1;
        public String defaultAid;

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        public List<ModelConfig> models = new ArrayList<ModelConfig>();

        public List<AgentConfig> agents = new ArrayList<AgentConfig>();
    }

    public static class ModelsConfig {
        public Integer version = 1;
        public List<ModelConfig> models = new ArrayList<ModelConfig>();
    }

    public static class ModelConfig {
        public Long id;
        public String name;
        public Integer provider;
        public Integer modelType = 0;
        public String modelName;
        public String url;
        public String apiKey;
        public String createTime;
        public String modifyTime;
    }

    public static class AgentConfig {
        public String aid;
        public String name;
        public Integer type;
        public String avatar;
        public String description;
        public AgentSettings agentConfig;
        public Map<String, Object> experConfig;
        public String createTime;
        public String modifyTime;
    }

    public static class AgentSettings {
        public String systemPrompt;
        public ChatModelBinding chatModel;
        public ChatMemorySettings chatMemoryConfig;
        public Object pluginConfig;
    }

    public static class ChatModelBinding {
        @JsonProperty("default_model_id")
        public Long defaultModelId;

        @JsonProperty("model_ids")
        public List<Long> modelIds = new ArrayList<Long>();
    }

    public static class ChatMemorySettings {
        public Integer type = 1;
        public String memoryConfig;
    }
}
