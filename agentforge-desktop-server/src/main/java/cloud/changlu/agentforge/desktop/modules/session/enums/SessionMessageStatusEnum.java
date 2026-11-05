package cloud.changlu.agentforge.desktop.modules.session.enums;

public enum SessionMessageStatusEnum {
    CREATED(1, "创建"),
    RUNNING(2, "处理中"),
    FAILED(3, "失败"),
    CANCELLED(4, "取消"),
    SUCCEEDED(5, "成功");

    private final Integer code;
    private final String description;

    SessionMessageStatusEnum(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public Integer getCode() { return code; }
    public String getDescription() { return description; }
}
