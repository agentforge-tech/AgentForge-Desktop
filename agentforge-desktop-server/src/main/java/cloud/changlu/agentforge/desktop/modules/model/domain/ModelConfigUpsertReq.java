package cloud.changlu.agentforge.desktop.modules.model.domain;

public class ModelConfigUpsertReq {
    private Long id;
    private String name;
    private Integer provider;
    private Integer modelType;
    private String modelName;
    private String url;
    private String apiKey;
    private Boolean supportsImage;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getProvider() { return provider; }
    public void setProvider(Integer provider) { this.provider = provider; }
    public Integer getModelType() { return modelType; }
    public void setModelType(Integer modelType) { this.modelType = modelType; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public Boolean getSupportsImage() { return supportsImage; }
    public void setSupportsImage(Boolean supportsImage) { this.supportsImage = supportsImage; }
}
