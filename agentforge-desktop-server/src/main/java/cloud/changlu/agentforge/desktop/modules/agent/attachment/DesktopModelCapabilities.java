package cloud.changlu.agentforge.desktop.modules.agent.attachment;

import cloud.changlu.agentforge.agent.config.LlmModelConfig;
import cloud.changlu.agentforge.agent.config.LlmModelConfigRegistry;
import cloud.changlu.agentforge.desktop.store.DesktopConfigStore;
import cloud.changlu.agentforge.model.registry.enums.LlmEnum;
import org.springframework.stereotype.Component;

/**
 * 把桌面模型配置映射为附件路由需要的模态能力。
 *
 * <p>优先使用模型配置中显式声明的 {@code supportsImage}；未声明时回退到框架模型注册表。
 *
 * @author changlu
 * @since 2026-11-22
 */
@Component
public class DesktopModelCapabilities {

    public boolean supportsImage(DesktopConfigStore.ModelConfig model) {
        if (model == null) {
            return false;
        }
        if (model.supportsImage != null) {
            return model.supportsImage.booleanValue();
        }
        boolean registered = LlmModelConfigRegistry.hasExactConfig(model.url, model.modelName)
                || LlmModelConfigRegistry.hasModelNameConfig(model.modelName);
        if (registered) {
            LlmModelConfig config =
                    LlmModelConfigRegistry.getConfig(model.url, model.modelName);
            return config != null && config.isSupportsImage();
        }
        // 未注册的自定义模型：Anthropic 协议默认按视觉模型处理，其余保持文本能力。
        return providerCode(model) == LlmEnum.ANTHROPIC.getCode();
    }

    public int providerCode(DesktopConfigStore.ModelConfig model) {
        if (model == null || model.provider == null) {
            return LlmEnum.OPENAI.getCode();
        }
        return model.provider;
    }

    public boolean requiresInlineImage(DesktopConfigStore.ModelConfig model) {
        return providerCode(model) == LlmEnum.ANTHROPIC.getCode();
    }
}
