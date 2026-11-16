package cloud.changlu.agentforge.desktop.modules.chat.append;

/** 追加消息持久化状态，与流式事件中的数字状态保持一致。 */
public enum UserMessageAppendStatus {
    QUEUED(1),
    EFFECTIVE(2),
    DEFERRED(3),
    CANCELED(4);

    private final int code;

    UserMessageAppendStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}