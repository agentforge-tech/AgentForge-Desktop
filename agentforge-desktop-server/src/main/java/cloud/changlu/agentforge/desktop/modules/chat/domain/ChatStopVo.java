package cloud.changlu.agentforge.desktop.modules.chat.domain;

import cloud.changlu.agentforge.desktop.modules.session.enums.SessionMessageStatusEnum;

/** 一次停止请求的确定结果；消息状态是最终取消结果的唯一权威来源。 */
public class ChatStopVo {
    private Long messageId;
    private boolean cancelRequested;
    private Integer status;
    private String statusName;
    private boolean timedOut;

    public static ChatStopVo of(
            Long messageId,
            boolean cancelRequested,
            SessionMessageStatusEnum status,
            boolean timedOut) {
        ChatStopVo vo = new ChatStopVo();
        vo.messageId = messageId;
        vo.cancelRequested = cancelRequested;
        vo.status = status == null ? null : status.getCode();
        vo.statusName = status == null ? null : status.name();
        vo.timedOut = timedOut;
        return vo;
    }

    public Long getMessageId() {
        return messageId;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public Integer getStatus() {
        return status;
    }

    public String getStatusName() {
        return statusName;
    }

    public boolean isTimedOut() {
        return timedOut;
    }
}
