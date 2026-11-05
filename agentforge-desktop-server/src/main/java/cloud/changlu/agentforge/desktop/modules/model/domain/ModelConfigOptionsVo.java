package cloud.changlu.agentforge.desktop.modules.model.domain;

import java.util.List;

public class ModelConfigOptionsVo {
    private List<ModelConfigOptionVo> providers;
    private List<ModelConfigOptionVo> modelTypes;

    public List<ModelConfigOptionVo> getProviders() { return providers; }
    public void setProviders(List<ModelConfigOptionVo> providers) { this.providers = providers; }
    public List<ModelConfigOptionVo> getModelTypes() { return modelTypes; }
    public void setModelTypes(List<ModelConfigOptionVo> modelTypes) { this.modelTypes = modelTypes; }
}
