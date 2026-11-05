package cloud.changlu.agentforge.desktop.protocol.processor.think;

import cloud.changlu.agentforge.desktop.protocol.StreamEventResponse;
import cloud.changlu.agentforge.desktop.protocol.enums.StreamEventEnums;
import cloud.changlu.agentforge.desktop.protocol.processor.IStreamSceneProcessor;

public class AiThinkProcessor implements IStreamSceneProcessor {
    @Override
    public String getSceneCode() { return StreamEventEnums.EventEnum.THINK.getCode(); }

    public static StreamEventResponse respThink(String content, Boolean isFinish) {
        return StreamEventResponse.of(
                        StreamEventEnums.EventEnum.THINK, StreamEventEnums.TypeEnum.TEXT)
                .content(content)
                .finish(isFinish);
    }
}
