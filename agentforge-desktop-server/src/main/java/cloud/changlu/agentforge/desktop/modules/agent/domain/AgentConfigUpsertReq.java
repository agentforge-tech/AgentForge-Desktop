package cloud.changlu.agentforge.desktop.modules.agent.domain;

import java.util.List;

public class AgentConfigUpsertReq {
    private String aid;
    private String name;
    private Integer type;
    private String avatar;
    private String description;
    private Long defaultModelId;
    private List<Long> modelIds;
    private String systemPrompt;
    private Integer chatMemoryType;
    private Integer chatMemoryMaxMessages;

    public String getAid() { return aid; }
    public void setAid(String aid) { this.aid = aid; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getType() { return type; }
    public void setType(Integer type) { this.type = type; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getDefaultModelId() { return defaultModelId; }
    public void setDefaultModelId(Long defaultModelId) { this.defaultModelId = defaultModelId; }
    public List<Long> getModelIds() { return modelIds; }
    public void setModelIds(List<Long> modelIds) { this.modelIds = modelIds; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public Integer getChatMemoryType() { return chatMemoryType; }
    public void setChatMemoryType(Integer chatMemoryType) { this.chatMemoryType = chatMemoryType; }
    public Integer getChatMemoryMaxMessages() { return chatMemoryMaxMessages; }
    public void setChatMemoryMaxMessages(Integer chatMemoryMaxMessages) { this.chatMemoryMaxMessages = chatMemoryMaxMessages; }
}
