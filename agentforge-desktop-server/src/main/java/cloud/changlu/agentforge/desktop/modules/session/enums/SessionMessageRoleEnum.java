package cloud.changlu.agentforge.desktop.modules.session.enums;

public enum SessionMessageRoleEnum {
    USER(1, "用户"),
    AI(2, "AI");

    private final Integer code;
    private final String description;

    SessionMessageRoleEnum(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    public Integer getCode() { return code; }
    public String getDescription() { return description; }
}
