package cloud.changlu.agentforge.desktop.modules.agent.service;

import cloud.changlu.agentforge.desktop.modules.agent.domain.AgentConfigUpsertReq;
import cloud.changlu.agentforge.desktop.modules.agent.domain.AgentConfigVo;
import cloud.changlu.agentforge.desktop.modules.agent.runtime.AgentRuntimeService;
import cloud.changlu.agentforge.desktop.store.DesktopConfigStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class AgentConfigService {

    private static final String DEFAULT_PROMPT = "你是一名专业的 AI 助手，请准确、清晰地回答用户问题。";

    private final DesktopConfigStore configStore;
    private final AgentRuntimeService runtimeService;

    public AgentConfigService(DesktopConfigStore configStore, AgentRuntimeService runtimeService) {
        this.configStore = configStore;
        this.runtimeService = runtimeService;
    }

    public List<AgentConfigVo> list() {
        List<AgentConfigVo> result = new ArrayList<AgentConfigVo>();
        for (DesktopConfigStore.AgentConfig agent : configStore.listAgents()) {
            result.add(toVo(agent));
        }
        return result;
    }

    public AgentConfigVo get(String aid) {
        DesktopConfigStore.AgentConfig agent = configStore.findAgent(aid);
        if (agent == null) {
            throw new IllegalArgumentException("Agent 不存在或已被删除: " + aid);
        }
        return toVo(agent);
    }

    public AgentConfigVo upsert(AgentConfigUpsertReq req) {
        if (req == null) {
            throw new IllegalArgumentException("Agent 配置不能为空");
        }
        DesktopConfigStore.AgentConfig agent = new DesktopConfigStore.AgentConfig();
        agent.aid = req.getAid();
        agent.name = req.getName();
        agent.type = req.getType() == null ? 1 : req.getType();
        agent.avatar = req.getAvatar() == null ? "" : req.getAvatar();
        agent.description = req.getDescription() == null ? "" : req.getDescription();

        DesktopConfigStore.AgentSettings settings = new DesktopConfigStore.AgentSettings();
        settings.systemPrompt =
                req.getSystemPrompt() == null || req.getSystemPrompt().trim().isEmpty()
                        ? DEFAULT_PROMPT
                        : req.getSystemPrompt().trim();
        settings.chatModel = new DesktopConfigStore.ChatModelBinding();
        settings.chatModel.defaultModelId = req.getDefaultModelId();
        settings.chatModel.modelIds =
                req.getModelIds() == null ? new ArrayList<Long>() : req.getModelIds();
        settings.chatMemoryConfig = new DesktopConfigStore.ChatMemorySettings();
        settings.chatMemoryConfig.type = req.getChatMemoryType() == null ? 1 : req.getChatMemoryType();
        int maxMessages =
                req.getChatMemoryMaxMessages() == null || req.getChatMemoryMaxMessages() <= 0
                        ? 1000
                        : req.getChatMemoryMaxMessages();
        settings.chatMemoryConfig.memoryConfig = "{\"maxMessage\":" + maxMessages + "}";
        agent.agentConfig = settings;

        DesktopConfigStore.AgentConfig existing = configStore.findAgent(req.getAid());
        if (existing != null) {
            agent.experConfig = existing.experConfig;
        }

        DesktopConfigStore.AgentConfig saved = configStore.upsertAgent(agent);
        runtimeService.evict(saved.aid);

        AgentConfigVo vo = toVo(saved);
        try {
            DesktopConfigStore.ModelConfig model =
                    configStore.findModel(saved.agentConfig.chatModel.defaultModelId);
            if (model == null) {
                throw new IllegalArgumentException("默认模型不存在");
            }
            runtimeService.warmUp(saved, model);
            vo.setInitOk(true);
        } catch (Exception e) {
            vo.setInitOk(false);
            vo.setInitError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return vo;
    }

    public void delete(String aid) {
        runtimeService.evict(aid);
        configStore.deleteAgent(aid);
    }

    private AgentConfigVo toVo(DesktopConfigStore.AgentConfig agent) {
        AgentConfigVo vo = new AgentConfigVo();
        vo.setAid(agent.aid);
        vo.setName(agent.name);
        vo.setType(agent.type);
        vo.setAvatar(agent.avatar);
        vo.setDescription(agent.description);
        vo.setCreateTime(agent.createTime);
        vo.setModifyTime(agent.modifyTime);
        vo.setExperConfig(agent.experConfig);
        if (agent.agentConfig != null) {
            vo.setSystemPrompt(agent.agentConfig.systemPrompt);
            if (agent.agentConfig.chatModel != null) {
                vo.setDefaultModelId(agent.agentConfig.chatModel.defaultModelId);
                vo.setModelIds(new ArrayList<Long>(agent.agentConfig.chatModel.modelIds));
            }
            if (agent.agentConfig.chatMemoryConfig != null) {
                vo.setChatMemoryType(agent.agentConfig.chatMemoryConfig.type);
                vo.setChatMemoryMaxMessages(
                        parseMaxMessages(agent.agentConfig.chatMemoryConfig.memoryConfig));
            }
        }
        return vo;
    }

    private static int parseMaxMessages(String memoryConfig) {
        if (memoryConfig == null) {
            return 1000;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\"maxMessage\"\\s*:\\s*(\\d+)").matcher(memoryConfig);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return 1000;
            }
        }
        return 1000;
    }
}
