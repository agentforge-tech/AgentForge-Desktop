package cloud.changlu.agentforge.desktop.modules.session.enums;

public enum SessionStatusEnum {
    IDLE(0, "空闲"),
    RUNNING(1, "运行中"),
    FINISHED(2, "完成"),
    FAILED(3, "失败");

    private final Integer code;
    private final String description;

    SessionStatusEnum(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public Integer getCode() { return code; }
    public String getDescription() { return description; }
}
