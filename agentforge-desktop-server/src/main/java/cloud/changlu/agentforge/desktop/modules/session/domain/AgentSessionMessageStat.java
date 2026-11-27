package cloud.changlu.agentforge.desktop.modules.session.domain;

/**
 * 会话消息聚合统计结果。
 *
 * <p>会话列表一次性返回多条会话时，用一条分组查询替代逐会话统计，避免 N+1 查询。
 */
public class AgentSessionMessageStat {
    private Long sessionId;
    private Integer messageCount;
    private String lastMessageAt;

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Integer getMessageCount() { return messageCount; }
    public void setMessageCount(Integer messageCount) { this.messageCount = messageCount; }
    public String getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(String lastMessageAt) { this.lastMessageAt = lastMessageAt; }
}