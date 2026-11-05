package cloud.changlu.agentforge.desktop.protocol.processor.tool;

import cloud.changlu.agentforge.desktop.protocol.StreamEventResponse;
import cloud.changlu.agentforge.desktop.protocol.ToolCallResult;
import cloud.changlu.agentforge.desktop.protocol.enums.StreamEventEnums;
import cloud.changlu.agentforge.desktop.protocol.processor.IStreamSceneProcessor;

public class AiToolCallProcessor implements IStreamSceneProcessor {
    @Override
    public String getSceneCode() { return StreamEventEnums.EventEnum.TOOL_CALL.getCode(); }

    public static StreamEventResponse toolCallCard(ToolCallResult toolCallResult, Boolean isFinish) {
        return StreamEventResponse.of(
                        StreamEventEnums.EventEnum.TOOL_CALL, StreamEventEnums.TypeEnum.TOOL_CARD)
                .content(toolCallResult)
                .finish(isFinish);
    }
}
