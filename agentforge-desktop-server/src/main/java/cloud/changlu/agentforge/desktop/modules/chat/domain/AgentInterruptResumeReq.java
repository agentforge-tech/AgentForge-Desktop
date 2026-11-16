package cloud.changlu.agentforge.desktop.modules.chat.domain;

import java.util.List;

/** 提交一个 HITL 批次的全部审批结果或 AskUser 回答。 */
public class AgentInterruptResumeReq {
    private String batchId;
    private List<AgentInterruptResponseReq> responses;

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public List<AgentInterruptResponseReq> getResponses() {
        return responses;
    }

    public void setResponses(List<AgentInterruptResponseReq> responses) {
        this.responses = responses;
    }
}