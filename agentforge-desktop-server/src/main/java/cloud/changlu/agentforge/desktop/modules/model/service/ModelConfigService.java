package cloud.changlu.agentforge.desktop.modules.model.service;

import cloud.changlu.agentforge.desktop.modules.model.domain.ModelConfigOptionVo;
import cloud.changlu.agentforge.desktop.modules.model.domain.ModelConfigOptionsVo;
import cloud.changlu.agentforge.desktop.modules.model.domain.ModelConfigTestReq;
import cloud.changlu.agentforge.desktop.modules.model.domain.ModelConfigTestVo;
import cloud.changlu.agentforge.desktop.modules.model.domain.ModelConfigUpsertReq;
import cloud.changlu.agentforge.desktop.modules.agent.runtime.AgentRuntimeService;
import cloud.changlu.agentforge.desktop.modules.model.domain.ModelConfigVo;
import cloud.changlu.agentforge.desktop.store.DesktopConfigStore;
import cloud.changlu.agentforge.model.chat.ChatModel;
import cloud.changlu.agentforge.model.chat.message.UserMessage;
import cloud.changlu.agentforge.model.chat.request.ChatRequest;
import cloud.changlu.agentforge.model.chat.request.DefaultChatRequestParameters;
import cloud.changlu.agentforge.model.chat.request.ToolChoice;
import cloud.changlu.agentforge.model.chat.response.ChatResponse;
import cloud.changlu.agentforge.model.http.HttpUserAgent;
import cloud.changlu.agentforge.model.registry.LlmFactory;
import cloud.changlu.agentforge.model.registry.config.LlmBasicConfig;
import cloud.changlu.agentforge.model.registry.constant.ChatModelProperties;
import cloud.changlu.agentforge.model.tool.spec.ToolParameters;
import cloud.changlu.agentforge.model.tool.spec.ToolSpecification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Service
public class ModelConfigService {

    private static final String PROBE_TOOL_NAME = "agentforge_function_call_probe";

    private final DesktopConfigStore configStore;
    private final AgentRuntimeService runtimeService;

    public ModelConfigService(DesktopConfigStore configStore, AgentRuntimeService runtimeService) {
        this.configStore = configStore;
        this.runtimeService = runtimeService;
    }

    public List<ModelConfigVo> list() {
        List<ModelConfigVo> result = new ArrayList<ModelConfigVo>();
        for (DesktopConfigStore.ModelConfig model : configStore.listModels()) {
            result.add(toVo(model));
        }
        return result;
    }

    public ModelConfigVo get(Long id) {
        DesktopConfigStore.ModelConfig model = configStore.findModel(id);
        if (model == null) {
            throw new IllegalArgumentException("模型不存在或已被删除: " + id);
        }
        return toVo(model);
    }

    public ModelConfigVo upsert(ModelConfigUpsertReq req) {
        if (req == null) {
            throw new IllegalArgumentException("模型配置不能为空");
        }
        DesktopConfigStore.ModelConfig model = new DesktopConfigStore.ModelConfig();
        model.id = req.getId();
        model.name = req.getName();
        model.provider = req.getProvider();
        model.modelType = req.getModelType();
        model.modelName = req.getModelName();
        model.url = req.getUrl();
        model.apiKey = req.getApiKey();
        ModelConfigVo result = toVo(configStore.upsertModel(model));
        runtimeService.clearAll();
        return result;
    }

    public void delete(Long id) {
        configStore.deleteModel(id);
        runtimeService.clearAll();
    }

    public ModelConfigOptionsVo options() {
        ModelConfigOptionsVo vo = new ModelConfigOptionsVo();
        vo.setProviders(
                Arrays.asList(
                        new ModelConfigOptionVo(1, "OpenAI"),
                        new ModelConfigOptionVo(2, "Anthropic")));
        vo.setModelTypes(Collections.singletonList(new ModelConfigOptionVo(0, "chat")));
        return vo;
    }

    public ModelConfigTestVo testConnectivity(ModelConfigTestReq req) {
        ModelConfigTestVo vo = new ModelConfigTestVo();
        DesktopConfigStore.ModelConfig model = resolveModel(req);
        vo.setModelId(model.id);
        try {
            ChatModel chatModel = buildChatModel(model);
            String response = chatModel.chat("ping");
            if (response == null || response.trim().isEmpty()) {
                return failure(vo, "模型连通性测试失败: 模型未返回有效内容");
            }
            return success(vo, "模型连通性测试成功");
        } catch (Exception e) {
            return failure(vo, "模型连通性测试失败: " + message(e));
        }
    }

    public ModelConfigTestVo testFunctionCall(ModelConfigTestReq req) {
        ModelConfigTestVo vo = new ModelConfigTestVo();
        DesktopConfigStore.ModelConfig model = resolveModel(req);
        vo.setModelId(model.id);
        try {
            ChatModel chatModel = buildChatModel(model);
            ToolSpecification probeTool =
                    ToolSpecification.builder()
                            .name(PROBE_TOOL_NAME)
                            .description("Echo the given text back. Always call this tool.")
                            .parameters(
                                    ToolParameters.builder()
                                            .addProperty("text", "string", "the text to echo", true)
                                            .build())
                            .build();
            DefaultChatRequestParameters parameters =
                    DefaultChatRequestParameters.builder()
                            .tools(Collections.singletonList(probeTool))
                            .toolChoice(ToolChoice.REQUIRED)
                            .build();
            ChatResponse response =
                    chatModel.chat(
                            ChatRequest.builder()
                                    .message(
                                            UserMessage.from(
                                                    "Call the "
                                                            + PROBE_TOOL_NAME
                                                            + " tool with text \"ping\"."))
                                    .parameters(parameters)
                                    .build());
            boolean supported =
                    response.aiMessage() != null && response.aiMessage().hasToolExecutionRequests();
            if (supported) {
                return success(vo, "模型支持 Function Call");
            }
            return failure(vo, "模型未返回工具调用，可能不支持 Function Call");
        } catch (Exception e) {
            return failure(vo, "Function Call 测试失败: " + message(e));
        }
    }

    private ChatModel buildChatModel(DesktopConfigStore.ModelConfig model) {
        ChatModelProperties properties =
                ChatModelProperties.builder()
                        .timeout(120000)
                        .returnThinking(true)
                        .userAgent(HttpUserAgent.productUserAgent("AgentForge-Desktop", "0.1.0"))
                        .build();
        LlmBasicConfig config =
                LlmBasicConfig.builder()
                        .provider(model.provider)
                        .url(model.url)
                        .modelName(model.modelName)
                        .apiKey(configStore.effectiveApiKey(model))
                        .props(properties.toProperties())
                        .build();
        return LlmFactory.buildChatModel(config);
    }

    private DesktopConfigStore.ModelConfig resolveModel(ModelConfigTestReq req) {
        if (req == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        DesktopConfigStore.ModelConfig model = new DesktopConfigStore.ModelConfig();
        if (req.getModelId() != null) {
            DesktopConfigStore.ModelConfig existing = configStore.findModel(req.getModelId());
            if (existing == null) {
                throw new IllegalArgumentException("模型不存在或已被删除: " + req.getModelId());
            }
            model.id = existing.id;
            model.name = existing.name;
            model.provider = existing.provider;
            model.modelType = existing.modelType;
            model.modelName = existing.modelName;
            model.url = existing.url;
            model.apiKey = existing.apiKey;
        }
        if (req.getProvider() != null) {
            model.provider = req.getProvider();
        }
        if (req.getModelType() != null) {
            model.modelType = req.getModelType();
        }
        if (req.getModelName() != null && !req.getModelName().trim().isEmpty()) {
            model.modelName = req.getModelName().trim();
        }
        if (req.getUrl() != null && !req.getUrl().trim().isEmpty()) {
            model.url = req.getUrl().trim();
        }
        if (req.getApiKey() != null && !req.getApiKey().trim().isEmpty()) {
            model.apiKey = req.getApiKey().trim();
        }
        if (model.provider == null || model.modelName == null) {
            throw new IllegalArgumentException("请提供 modelId 或完整的 provider/modelName");
        }
        return model;
    }

    private static ModelConfigVo toVo(DesktopConfigStore.ModelConfig model) {
        ModelConfigVo vo = new ModelConfigVo();
        vo.setId(model.id);
        vo.setName(model.name);
        vo.setProvider(model.provider);
        vo.setModelType(model.modelType);
        vo.setModelName(model.modelName);
        vo.setUrl(model.url);
        vo.setApiKey(model.apiKey);
        vo.setCreateTime(model.createTime);
        vo.setModifyTime(model.modifyTime);
        return vo;
    }

    private static ModelConfigTestVo success(ModelConfigTestVo vo, String message) {
        vo.setSuccess(true);
        vo.setMessage(message);
        return vo;
    }

    private static ModelConfigTestVo failure(ModelConfigTestVo vo, String message) {
        vo.setSuccess(false);
        vo.setMessage(message);
        return vo;
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
