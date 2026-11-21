package cloud.changlu.agentforge.desktop.modules.chat.service;

import cloud.changlu.agentforge.desktop.modules.chat.domain.AgentInterruptResumeReq;
import cloud.changlu.agentforge.desktop.modules.chat.domain.AgentInterruptResumeVo;
import cloud.changlu.agentforge.desktop.modules.chat.domain.ChatStopVo;
import cloud.changlu.agentforge.desktop.modules.chat.domain.ChatStreamRequest;
import cloud.changlu.agentforge.desktop.modules.chat.domain.ChatTaskSubmitVo;

/** 二段式对话任务的提交、恢复和停止服务。 */
public interface ChatTaskService {

    ChatTaskSubmitVo submit(ChatStreamRequest request);

    ChatStopVo stop(Long messageId);

    AgentInterruptResumeVo resumeInterrupt(AgentInterruptResumeReq request);
}
