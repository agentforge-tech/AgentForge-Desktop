package cloud.changlu.agentforge.desktop.modules.model.domain;

public class ModelConfigOptionVo {
    private Integer code;
    private String name;

    public ModelConfigOptionVo() {}

    public ModelConfigOptionVo(Integer code, String name) {
        this.code = code;
        this.name = name;
    }

    public Integer getCode() { return code; }
    public void setCode(Integer code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
